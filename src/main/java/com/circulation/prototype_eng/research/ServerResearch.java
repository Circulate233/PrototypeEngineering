package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.integration.ftb.FtbOwnerTeamResolver;
import com.circulation.prototype_eng.research.manual.MinecraftManualResearchInventory;
import com.feed_the_beast.ftblib.lib.data.Universe;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Authoritative server research session; its file has one writer and is never registered in MapStorage. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerResearch {
    @Nullable private static volatile Session session;

    private ServerResearch() { }

    /** FML serverStarting calls this after the overworld and frozen registries are available. */
    public static synchronized void start(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        if (session != null) { throw new IllegalStateException("A research server session is already installed"); }
        var overworld = Objects.requireNonNull(server.getWorld(0), "Research startup requires the overworld");
        var registry = ResearchRegistry.INSTANCE;
        var resources = NetworkResourceRegistry.INSTANCE;
        var file = new ResearchStateFile(overworld.getSaveHandler().getMapFileFromName("prototype_eng_research").toPath(),
            new ResearchWorldNbt(registry, resources, new ResourceKeyNbt()));
        var world = new ResearchWorld(registry, resources, ResearchPermissionPolicy.DEFAULT, snapshot -> {
            try { file.write(snapshot); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
        try { file.read().ifPresent(world::restore); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        session = new Session(server, overworld, world);
    }

    /** Exact server identity rejects callbacks from an earlier integrated-server session. */
    @SuppressWarnings("unused") // Public Java research entry for server hosts and addons.
    public static ResearchWorld forServer(MinecraftServer server) { return active(server).world; }

    /** Called once by the existing server END scheduler boundary, never by a second tick subscriber. */
    public static void tick(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        var current = session;
        if (current == null || current.closing) { return; }
        if (current.server != server || server.getWorld(0) != current.overworld) {
            throw new IllegalStateException("Research tick changed its server or overworld identity");
        }
        var online = onlinePlayers(current);
        var seeds = new ObjectOpenHashSet<>(current.world.trackedPlayers());
        seeds.addAll(online.keySet());
        var captures = capture(current, seeds);
        if (unavailable(captures)) { return; }
        var proof = proof(current, captures, online);
        if (!proof.getAsBoolean()) { return; }
        // A research checkpoint can reach disk before vanilla world time. Persisted research time
        // therefore advances independently once per server tick and never moves backwards on reload.
        long time = Math.max(Math.incrementExact(current.world.serverTime()), current.overworld.getTotalWorldTime());
        current.world.tick(time, captures, online.keySet(), id -> inventory(current, id), proof);
    }

    /** Only the actual actor can create a tier-zero session; tiered device admission has a separate host. */
    @SuppressWarnings("unused") // Public Java operation until packet/command adapters are attached.
    public static TeamResearchStart.Result startManual(EntityPlayerMP actor, ResourceLocation id, int target) {
        var current = actorSession(actor);
        requireManual(id, target);
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) { return staleStart(); }
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!membership.accepted()) {
            return new TeamResearchStart.Result(TeamResearchStart.Status.PERMISSION_DENIED, null,
                ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
        }
        var permission = permission(current, actor, membership);
        if (permission == null) { return staleStart(); }
        return current.world.start(membership, permission, id, target,
            new TeamResearchStart.Environment(0, 0, 0, Object2LongMaps.emptyMap()), operationTime(current),
            online.keySet(), proof(current, captures, online));
    }

    @SuppressWarnings("unused") // Public Java operation; login deliberately does not resume research.
    public static ManualResearch.Status takeoverManual(EntityPlayerMP actor, ResourceLocation id, int target) {
        var current = actorSession(actor);
        requireManual(id, target);
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) { return ManualResearch.Status.STALE_CONTEXT; }
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!membership.accepted()) { return ManualResearch.Status.CONTRIBUTOR_UNAVAILABLE; }
        return current.world.takeover(membership, actor.getUniqueID(), id, target,
            operationTime(current), online.keySet(), proof(current, captures, online));
    }

    /** Mark closing before IO; a failed final checkpoint retains the exact session for retry. */
    public static synchronized void stop(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        var current = session;
        if (current == null) { return; }
        if (current.server != server) { throw new IllegalStateException("Research shutdown changed server identity"); }
        current.closing = true;
        // The file writer retains its absolute path, so serverStopped can retry after world unload.
        current.world.close(operationTime(current));
        session = null;
    }

    /** Idempotent serverStopped fallback for a retained failed-close session. */
    public static synchronized void stop() {
        var current = session;
        if (current != null) { stop(current.server); }
    }

    @SubscribeEvent
    public static void onWorldSave(WorldEvent.Save event) {
        var current = session;
        if (current == null || event.getWorld() != current.overworld) { return; }
        requireThread(current.server);
        current.world.checkpoint(operationTime(current));
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        var current = session;
        if (current != null && event.getWorld() == current.overworld) { stop(current.server); }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        var current = session;
        if (current == null || current.closing || !(event.player instanceof EntityPlayerMP player)
            || !belongsTo(current, player) || current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) { return; }
        requireThread(current.server);
        current.world.playerLogin(player.getUniqueID(), operationTime(current));
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        var current = session;
        if (current == null || current.closing || !(event.player instanceof EntityPlayerMP player) || !belongsTo(current, player)) { return; }
        requireThread(current.server);
        var registered = current.server.getPlayerList().getPlayerByUUID(player.getUniqueID());
        if (registered != null && registered != player) { return; }
        var remaining = new ObjectOpenHashSet<>(onlinePlayers(current).keySet());
        remaining.remove(player.getUniqueID());
        current.world.playerLogout(player.getUniqueID(), operationTime(current), remaining);
    }

    private static Session active(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        var current = session;
        if (current == null || current.server != server || current.closing || server.getWorld(0) != current.overworld) {
            throw new IllegalStateException("No active research session for this server");
        }
        return current;
    }

    private static Session actorSession(EntityPlayerMP actor) {
        Objects.requireNonNull(actor);
        var current = active(Objects.requireNonNull(actor.getServerWorld().getMinecraftServer()));
        if (!belongsTo(current, actor) || current.server.getPlayerList().getPlayerByUUID(actor.getUniqueID()) != actor) {
            throw new IllegalArgumentException("Manual research requires the actual online server player");
        }
        return current;
    }

    private static boolean belongsTo(Session current, EntityPlayerMP player) {
        return !(player instanceof FakePlayer) && !player.world.isRemote
            && player.getServerWorld().getMinecraftServer() == current.server;
    }

    private static Object2ObjectOpenHashMap<UUID, EntityPlayerMP> onlinePlayers(Session current) {
        requireThread(current.server);
        var result = new Object2ObjectOpenHashMap<UUID, EntityPlayerMP>();
        for (var player : current.server.getPlayerList().getPlayers()) {
            if (!belongsTo(current, player) || current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) { continue; }
            if (result.putIfAbsent(player.getUniqueID(), player) != null) { throw new IllegalStateException("Duplicate actual online research player"); }
        }
        return result;
    }

    private static Map<UUID, OwnerTeamResolution> capture(Session current, Set<UUID> seeds) {
        requireThread(current.server);
        var result = new Object2ObjectOpenHashMap<UUID, OwnerTeamResolution>();
        var teams = new Object2ObjectOpenHashMap<String, OwnerTeamResolution>();
        var pending = new ObjectArrayList<>(seeds);
        var scheduled = new ObjectOpenHashSet<>(seeds);
        for (int index = 0; index < pending.size(); index++) {
            var id = Objects.requireNonNull(pending.get(index));
            if (result.containsKey(id)) { continue; }
            var resolution = Objects.requireNonNull(current.resolver.resolve(id));
            if (!id.equals(resolution.ownerId())) { throw new IllegalStateException("Team resolver changed the queried player"); }
            result.put(id, resolution);
            if (resolution.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE) {
                return result; // An unavailable provider is a normal pause, never evidence of team dissolution.
            }
            if (!resolution.accepted()) { continue; }
            var previous = teams.putIfAbsent(resolution.teamId(), resolution);
            if (previous != null && !sameTeam(previous, resolution)) { throw new IllegalStateException("Conflicting current research team captures"); }
            for (var member : resolution.members()) { if (scheduled.add(member)) { pending.add(member); } }
        }
        // Every member returned by a team must resolve back to that same team, including offline members.
        for (var resolution : teams.values()) {
            for (var member : resolution.members()) {
                var actual = result.get(member);
                if (actual == null || !actual.accepted() || !sameTeam(resolution, actual)) {
                    throw new IllegalStateException("Research team capture contradicts a member's current team");
                }
            }
        }
        return result;
    }

    private static BooleanSupplier proof(Session current, Map<UUID, OwnerTeamResolution> captures,
                                         Map<UUID, EntityPlayerMP> online) {
        return () -> {
            requireThread(current.server);
            if (session != current || current.closing || current.server.getWorld(0) != current.overworld) { return false; }
            var nowOnline = onlinePlayers(current);
            if (!nowOnline.keySet().equals(online.keySet())) { return false; }
            for (var entry : online.entrySet()) { if (nowOnline.get(entry.getKey()) != entry.getValue()) { return false; } }
            for (var entry : captures.entrySet()) {
                var now = current.resolver.resolve(entry.getKey());
                if (now == null || !sameResolution(entry.getValue(), now)) { return false; }
            }
            return true;
        };
    }

    private static boolean sameResolution(OwnerTeamResolution left, OwnerTeamResolution right) {
        return left.ownerId().equals(right.ownerId()) && left.status() == right.status()
            && (!left.accepted() || sameTeam(left, right));
    }

    private static boolean sameTeam(OwnerTeamResolution left, OwnerTeamResolution right) {
        return left.teamId().equals(right.teamId()) && left.teamType() == right.teamType()
            && left.members().equals(right.members()) && left.teamOwnerId().equals(right.teamOwnerId());
    }

    private static boolean unavailable(Map<UUID, OwnerTeamResolution> captures) {
        for (var resolution : captures.values()) {
            if (resolution.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE) { return true; }
        }
        return false;
    }

    private static TeamResearchStart.Result staleStart() { return new TeamResearchStart.Result(TeamResearchStart.Status.STALE_CONTEXT, null, null); }

    private static @Nullable ResearchPermissionContext permission(Session current, EntityPlayerMP actor, OwnerTeamResolution membership) {
        if (!Universe.loaded() || Universe.get().server != current.server) { return null; }
        var player = Universe.get().getPlayer(actor.getUniqueID());
        if (player == null || player.team == null || !player.team.isValid() || !membership.teamId().equals(player.team.getUIDCode())) {
            return null;
        }
        return new ResearchPermissionContext(actor.getUniqueID(), membership.teamId(), null, membership.teamId(),
            player.team.isOwner(player), player.team.isModerator(player), current.server.getPlayerList().canSendCommands(actor.getGameProfile()));
    }

    private static void requireManual(ResourceLocation id, int target) {
        if (target < 1 || ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id)).stageForLevel(target).researchTier() != 0) {
            throw new IllegalArgumentException("This server operation requires tier-zero research");
        }
    }

    private static @Nullable ManualResearchInventory inventory(Session current, UUID id) {
        requireThread(current.server);
        var player = current.server.getPlayerList().getPlayerByUUID(id);
        return player != null && belongsTo(current, player) ? new MinecraftManualResearchInventory(player) : null;
    }

    private static long operationTime(Session current) {
        return Math.max(current.world.serverTime(), current.overworld.getTotalWorldTime());
    }

    private static void requireThread(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread()) { throw new IllegalStateException("Research operations require the authoritative server thread"); }
    }

    private static final class Session {
        private final MinecraftServer server;
        private final WorldServer overworld;
        private final ResearchWorld world;
        private final FtbOwnerTeamResolver resolver;
        private boolean closing;

        private Session(MinecraftServer server, WorldServer overworld, ResearchWorld world) {
            this.server = server;
            this.overworld = overworld;
            this.world = world;
            resolver = new FtbOwnerTeamResolver(server);
        }
    }
}
