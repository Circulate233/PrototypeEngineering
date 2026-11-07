package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.reward.RewardFragment;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.machine.ServerMachineIdentities;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.integration.ftb.FtbOwnerTeamResolver;
import com.circulation.prototype_eng.research.manual.MinecraftManualResearchInventory;
import com.circulation.prototype_eng.research.reward.MinecraftRewardItemDeliveryAdapter;
import com.feed_the_beast.ftblib.lib.data.Universe;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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

    /** Read-only capture; the scheduler takes ownership before research can execute or save. */
    @SuppressWarnings("resource") // The server lifecycle owns the borrowed network runtime.
    @Nullable public static PlannedExecution prepareTick(MinecraftServer server, PlannedTaskExecutor tasks, PlannedDeadline deadline) {
        requireThread(Objects.requireNonNull(server));
        var current = session;
        if (current == null || current.closing) { return null; }
        if (current.server != server || server.getWorld(0) != current.overworld) {
            throw new IllegalStateException("Research tick changed its server or overworld identity");
        }
        var online = onlinePlayers(current);
        var seeds = new ObjectOpenHashSet<>(current.world.trackedPlayers());
        seeds.addAll(online.keySet());
        var captures = capture(current, seeds);
        var proof = proof(current, captures, online);
        // A research checkpoint can reach disk before vanilla world time. Persisted research time
        // therefore advances independently once per server tick and never moves backwards on reload.
        long time = Math.max(Math.incrementExact(current.world.serverTime()), current.overworld.getTotalWorldTime());
        return current.world.prepareTick(time, captures, online.keySet(), id -> inventory(current, id), proof,
            current.stations, () -> ServerNetworkRuntime.forServer(server).refresh(), tasks, deadline);
    }

    /** Only the actual actor can create a tier-zero session; this direct entry needs no network publication. */
    @SuppressWarnings("unused") // Public Java operation until packet/command adapters are attached.
    public static TeamResearchStart.Result startManual(EntityPlayerMP actor, ResourceLocation id, int target) {
        var current = actorSession(actor);
        requireManual(id, target);
        return startCurrent(current, actor, id, target);
    }

    /**
     * Queue a player start through the existing server scheduler. Its MAIN action receives a fresh
     * network publication after old research/source work has settled. Actor and team authority are
     * captured again when executed; the caller supplies no device levels or resource vector.
     * The result callback runs once after the attempted admission and its world checkpoint succeed.
     * Unexecuted requests follow scheduler lifetime and are discarded during server shutdown.
     */
    @SuppressWarnings("unused") // Public Java operation until packet/command adapters are attached.
    public static void requestStart(EntityPlayerMP actor, ResourceLocation id, int target,
                                    Consumer<TeamResearchStart.Result> result) {
        requestStart(actor, id, target, () -> true, result);
    }

    /** A container can retain its exact open-window guard through admission callbacks. */
    public static void requestStart(EntityPlayerMP actor, ResourceLocation id, int target, BooleanSupplier ownerCurrent,
                                    Consumer<TeamResearchStart.Result> result) {
        requestStart(actor, id, target, null, ownerCurrent, result);
    }

    /** Bind admission to the team displayed by the original server-owned container projection. */
    @SuppressWarnings("resource")
    public static void requestStart(EntityPlayerMP actor, ResourceLocation id, int target, @Nullable String expectedTeam,
                                     BooleanSupplier ownerCurrent, Consumer<TeamResearchStart.Result> result) {
        var current = actorSession(actor);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id)).stageForLevel(target);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(staleStart());
                return;
            }
            result.accept(startCurrent(current, actor, id, target, expectedTeam, ownerCurrent));
        }, ExecutionMode.MAIN_THREAD);
    }

    /**
     * Claims the actual player's saved reward eligibility at the existing MAIN boundary. Selection
     * starts delivery, as in PN. Pending source work settles first; queued requests recheck the exact
     * server/player session and never accept a client-supplied recipient or item plan.
     */
    @SuppressWarnings("unused") // Public Java operation until packet/command adapters are attached.
    public static void requestReward(EntityPlayerMP actor, ResourceLocation id, int level, ResourceLocation group,
                                      Consumer<ResearchRewardClaimer.Result> result) {
        requestReward(actor, id, level, group, () -> true, result);
    }

    @SuppressWarnings("resource")
    public static void requestReward(EntityPlayerMP actor, ResourceLocation id, int level, ResourceLocation group,
                                     BooleanSupplier ownerCurrent, Consumer<ResearchRewardClaimer.Result> result) {
        var current = actorSession(actor);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id)).stageForLevel(level);
        Objects.requireNonNull(group);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(new ResearchRewardClaimer.Result(ResearchRewardClaimer.Status.STALE_CONTEXT,
                    "Reward actor is no longer current", Optional.empty()));
                return;
            }
            result.accept(current.world.claimReward(actor.getUniqueID(), id, level, group, operationTime(current),
                new MinecraftRewardItemDeliveryAdapter(actor), () -> currentActor(current, actor) && ownerCurrent.getAsBoolean()));
        }, ExecutionMode.MAIN_THREAD);
    }

    private static TeamResearchStart.Result startCurrent(Session current, EntityPlayerMP actor, ResourceLocation id, int target) {
        return startCurrent(current, actor, id, target, null, () -> true);
    }

    @SuppressWarnings("resource")
    private static TeamResearchStart.Result startCurrent(Session current, EntityPlayerMP actor, ResourceLocation id, int target,
                                                          @Nullable String expectedTeam, BooleanSupplier ownerCurrent) {
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) { return staleStart(); }
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!membership.accepted()) {
            return new TeamResearchStart.Result(TeamResearchStart.Status.PERMISSION_DENIED, null,
                ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
        }
        if (expectedTeam != null && !expectedTeam.equals(membership.teamId())) return staleStart();
        var permission = permission(current, actor, membership);
        if (permission == null) { return staleStart(); }
        var capturedCurrent = proof(current, captures, online);
        BooleanSupplier actorCurrent = () -> capturedCurrent.getAsBoolean() && ownerCurrent.getAsBoolean()
            && permission.equals(permission(current, actor, membership));
        if (ResearchRegistry.INSTANCE.require(id).researchTierForLevel(target) == 0) {
            return current.world.start(membership, permission, id, target,
                new TeamResearchStart.Environment(0, 0, 0, Object2LongMaps.emptyMap()), operationTime(current),
                online.keySet(), actorCurrent);
        }
        var network = ServerNetworkRuntime.forServer(current.server);
        var publication = network.plan();
        if (publication == null) { return staleStart(); }
        var admission = current.stations.admission(membership.teamId());
        return current.world.start(membership, permission, id, target, admission.environment(), operationTime(current),
            online.keySet(), () -> actorCurrent.getAsBoolean() && network.plan() == publication && admission.current());
    }

    @SuppressWarnings("unused") // Public Java operation; login deliberately does not resume research.
    public static ManualResearch.Status takeoverManual(EntityPlayerMP actor, ResourceLocation id, int target) {
        var current = actorSession(actor);
        requireManual(id, target);
        return takeoverCurrent(current, actor, id, target, null, 0, () -> true);
    }

    private static ManualResearch.Status takeoverCurrent(Session current, EntityPlayerMP actor, ResourceLocation id, int target,
                                                           @Nullable String expectedTeam, long expectedEntryId, BooleanSupplier ownerCurrent) {
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) { return ManualResearch.Status.STALE_CONTEXT; }
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!membership.accepted()) { return ManualResearch.Status.CONTRIBUTOR_UNAVAILABLE; }
        if (expectedTeam != null && !expectedTeam.equals(membership.teamId())) return ManualResearch.Status.STALE_CONTEXT;
        var authority = proof(current, captures, online);
        return current.world.takeover(membership, actor.getUniqueID(), id, target, expectedEntryId,
            operationTime(current), online.keySet(), () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean());
    }

    @SuppressWarnings("unused") // Retained public Java operation for callers without a container projection.
    public static void requestTakeover(EntityPlayerMP actor, ResourceLocation id, int target, BooleanSupplier ownerCurrent,
                                       Consumer<ManualResearch.Status> result) {
        requestTakeover(actor, id, target, null, 0, ownerCurrent, result);
    }

    /** Team and queue entry belong to the displayed session, not merely its research ID and level. */
    @SuppressWarnings("resource")
    public static void requestTakeover(EntityPlayerMP actor, ResourceLocation id, int target, @Nullable String expectedTeam,
                                       long expectedEntryId, BooleanSupplier ownerCurrent, Consumer<ManualResearch.Status> result) {
        var current = actorSession(actor);
        requireManual(id, target);
        if (expectedEntryId < 0) throw new IllegalArgumentException("Invalid expected research queue entry");
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> result.accept(currentActor(current, actor) && ownerCurrent.getAsBoolean()
            ? takeoverCurrent(current, actor, id, target, expectedTeam, expectedEntryId, ownerCurrent) : ManualResearch.Status.STALE_CONTEXT),
            ExecutionMode.MAIN_THREAD);
    }

    @SuppressWarnings("unused") // Retained public Java operation for callers without a container projection.
    public static void requestBlacklist(EntityPlayerMP actor, ResourceLocation id, boolean blacklisted, BooleanSupplier ownerCurrent,
                                        Consumer<ResearchPermissionPolicy.Decision> result) {
        requestBlacklist(actor, id, blacklisted, null, ownerCurrent, result);
    }

    @SuppressWarnings("resource")
    public static void requestBlacklist(EntityPlayerMP actor, ResourceLocation id, boolean blacklisted, @Nullable String expectedTeam,
                                         BooleanSupplier ownerCurrent, Consumer<ResearchPermissionPolicy.Decision> result) {
        var current = actorSession(actor);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id));
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) return;
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
                return;
            }
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (!membership.accepted()) {
                result.accept(ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
                return;
            }
            if (expectedTeam != null && !expectedTeam.equals(membership.teamId())) {
                result.accept(ResearchPermissionPolicy.Decision.TARGET_TEAM_MISMATCH);
                return;
            }
            var permission = permission(current, actor, membership);
            if (permission == null) { result.accept(ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER); return; }
            var authority = proof(current, captures, online);
            result.accept(current.world.setBlacklisted(membership, permission, id, blacklisted, operationTime(current),
                () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership))));
        }, ExecutionMode.MAIN_THREAD);
    }

    /** The callback receives null for a busy/stale read, never a replacement empty research state. */
    @SuppressWarnings("resource")
    public static void requestView(EntityPlayerMP actor, BooleanSupplier ownerCurrent, Consumer<ResearchPlayerView> result) {
        var current = actorSession(actor);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) { result.accept(null); return; }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (unavailable(captures)) {
                membership = OwnerTeamResolution.rejected(actor.getUniqueID(), OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE,
                    "Research team capture is temporarily unavailable");
            }
            var team = membership;
            var permission = viewPermission(current, actor, team);
            if (permission == null) { result.accept(null); return; }
            var authority = proof(current, captures, online);
            var view = current.world.project(actor.getUniqueID(), team, permission,
                () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean()
                    && permission.equals(viewPermission(current, actor, team)));
            result.accept(view);
        }, ExecutionMode.MAIN_THREAD);
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
    @SuppressWarnings("resource") // The server owns the borrowed scheduler.
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        var current = session;
        if (current == null || current.closing || !(event.player instanceof EntityPlayerMP player)
            || !belongsTo(current, player) || current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) { return; }
        requireThread(current.server);
        ServerTaskSchedulers.forServer(current.server).runAtBoundary(() -> {
            if (session == current && !current.closing && belongsTo(current, player)
                && current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) == player) {
                current.world.playerLogin(player.getUniqueID(), operationTime(current));
                releaseConfirmedRewards(current, player);
            }
        });
    }

    /** A confirmed drop may load after its owner logged in; clean it after the join finishes. */
    @SubscribeEvent
    public static void onRewardEntityJoin(EntityJoinWorldEvent event) {
        var current = session;
        if (current == null || current.closing || event.getWorld().isRemote
            || event.getWorld().getMinecraftServer() != current.server || !(event.getEntity() instanceof EntityItem item)) { return; }
        var stack = item.getItem();
        if (!stack.hasTagCompound() || !Objects.requireNonNull(stack.getTagCompound()).hasKey(MinecraftRewardItemDeliveryAdapter.DELIVERY_TAG)) { return; }
        requireThread(current.server);
        var identity = StrictNbt.requireCompound(Objects.requireNonNull(stack.getTagCompound()), MinecraftRewardItemDeliveryAdapter.DELIVERY_TAG);
        var id = UUID.fromString(StrictNbt.requireString(identity, "Player"));
        var player = current.server.getPlayerList().getPlayerByUUID(id);
        if (player != null) queueRewardCleanup(current, player);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) { cleanupCurrentPlayer(event.player); }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { cleanupCurrentPlayer(event.player); }

    private static void cleanupCurrentPlayer(EntityPlayer actor) {
        var current = session;
        if (current != null && actor instanceof EntityPlayerMP player && !player.world.isRemote) queueRewardCleanup(current, player);
    }

    @SuppressWarnings("resource") // Cleanup uses the existing server-owned MAIN queue.
    private static void queueRewardCleanup(Session current, EntityPlayerMP player) {
        var id = player.getUniqueID();
        if (!currentActor(current, player) || !current.rewardCleanup.add(id)) return;
        try {
            ServerTaskSchedulers.forServer(current.server).submit(() -> {
                current.rewardCleanup.remove(id);
                if (currentActor(current, player)) { releaseConfirmedRewards(current, player); }
            }, ExecutionMode.MAIN_THREAD);
        } catch (RuntimeException | Error failure) { current.rewardCleanup.remove(id); throw failure; }
    }

    @SubscribeEvent
    @SuppressWarnings("resource") // Logout follows retained source work on the existing scheduler boundary.
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        var current = session;
        if (current == null || current.closing || !(event.player instanceof EntityPlayerMP player) || !belongsTo(current, player)) { return; }
        requireThread(current.server);
        var registered = current.server.getPlayerList().getPlayerByUUID(player.getUniqueID());
        if (registered != null && registered != player) { return; }
        ServerTaskSchedulers.forServer(current.server).runAtBoundary(() -> {
            if (session != current || current.closing) { return; }
            var now = current.server.getPlayerList().getPlayerByUUID(player.getUniqueID());
            if (now != null && now != player) { return; }
            var remaining = new ObjectOpenHashSet<>(onlinePlayers(current).keySet());
            remaining.remove(player.getUniqueID());
            current.world.playerLogout(player.getUniqueID(), operationTime(current), remaining);
        });
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
            throw new IllegalArgumentException("Research requires the actual online server player");
        }
        return current;
    }

    private static boolean belongsTo(Session current, EntityPlayerMP player) {
        return !(player instanceof FakePlayer) && !player.world.isRemote
            && player.getServerWorld().getMinecraftServer() == current.server;
    }

    private static boolean currentActor(Session current, EntityPlayerMP actor) {
        requireThread(current.server);
        return session == current && !current.closing && current.server.getWorld(0) == current.overworld
            && belongsTo(current, actor) && current.server.getPlayerList().getPlayerByUUID(actor.getUniqueID()) == actor;
    }

    /** A crash after the research checkpoint may restore old delivery tags; remove only proven leaves. */
    private static void releaseConfirmedRewards(Session current, EntityPlayerMP player) {
        // A read-only snapshot may contain a CONFIRMED transition whose writer failed. Flush its
        // exact pending snapshot first; a nested world operation may only request a deferred save.
        current.world.checkpoint(operationTime(current));
        if (current.world.busy() || current.world.savePending()) return;
        var delivery = new MinecraftRewardItemDeliveryAdapter(player);
        for (var saved : current.world.snapshot().players()) {
            if (!saved.playerUuid().equals(player.getUniqueID())) { continue; }
            for (var claim : saved.rewardClaims()) {
                for (var child : claim.children()) {
                    for (var fragment : child.fragments().values()) {
                        if (!currentActor(current, player)) { return; }
                        if (fragment.status() == RewardFragment.Status.CONFIRMED) { delivery.release(fragment); }
                    }
                }
            }
            return;
        }
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

    private static @Nullable ResearchPermissionContext viewPermission(Session current, EntityPlayerMP actor, OwnerTeamResolution membership) {
        return membership.accepted() ? permission(current, actor, membership)
            : new ResearchPermissionContext(actor.getUniqueID(), null, actor.getUniqueID(), null,
                false, false, current.server.getPlayerList().canSendCommands(actor.getGameProfile()));
    }

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
        private final ResearchStations stations;
        private final ObjectOpenHashSet<UUID> rewardCleanup = new ObjectOpenHashSet<>();
        private boolean closing;

        private Session(MinecraftServer server, WorldServer overworld, ResearchWorld world) {
            this.server = server;
            this.overworld = overworld;
            this.world = world;
            resolver = new FtbOwnerTeamResolver(server);
            stations = new ResearchStations(ServerMachineIdentities.forServer(server), () -> requireThread(server));
        }
    }
}
