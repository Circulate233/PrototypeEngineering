package com.circulation.prototype_eng.structure.tool.effect;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.network.InitNetwork;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.End;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Forget;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Hud;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Section;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.BooleanSupplier;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerConstructionEffects {
    private static final long SYNC_NANOS = 1_000_000L;
    private static final int BYTES_PER_TICK = 64 * 1024;
    private static final int PACKETS_PER_TICK = 128;
    private static @Nullable Session current;

    private ServerConstructionEffects() {
    }

    public static Effect create(EntityPlayerMP owner, BlockPos anchor, boolean dismantle) {
        var world = owner.getServerWorld();
        var session = session(owner.server);
        var index = session.worlds.computeIfAbsent(world, _ -> new WorldIndex(world));
        return new Effect(session, index, owner, anchor.toLong(), dismantle);
    }

    /** Run after the native player chunk map, and before this tick's block changes. */
    public static void tick(MinecraftServer server, long deadlineNanos) {
        var active = current;
        if (active == null || active.server != server) return;
        long now = System.nanoTime();
        active.tick(Math.min(deadlineNanos - now, SYNC_NANOS), now);
    }

    public static void stop() {
        current = null;
    }

    private static Session session(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread()) throw new IllegalStateException("Construction effects require the server thread");
        var active = current;
        if (active == null) current = active = new Session(server);
        if (active.server != server) throw new IllegalStateException("Another server owns the construction effects");
        return active;
    }

    @SubscribeEvent
    public static void watch(ChunkWatchEvent.Watch event) {
        watch(event, true);
    }

    @SubscribeEvent
    public static void unwatch(ChunkWatchEvent.UnWatch event) {
        watch(event, false);
    }

    private static void watch(ChunkWatchEvent event, boolean watching) {
        var active = current;
        var chunk = event.getChunkInstance();
        if (active == null || chunk == null || !(chunk.getWorld() instanceof WorldServer world)) return;
        var index = active.worlds.get(world);
        if (index == null) return;
        var sectors = index.chunks.get(ChunkPos.asLong(chunk.x, chunk.z));
        if (sectors == null) return;
        var player = event.getPlayer();
        for (var sector : sectors) {
            if (watching) sector.watch(player, false);
            else sector.unwatch(player);
        }
    }

    @SubscribeEvent
    public static void worldUnloaded(WorldEvent.Unload event) {
        var active = current;
        if (active != null && event.getWorld() instanceof WorldServer world) active.worlds.remove(world);
    }

    private enum WorkResult {DONE, CONTINUE, WAIT}

    @FunctionalInterface
    private interface Work {
        WorkResult advance();
    }

    private static final class Session {
        final MinecraftServer server;
        final Reference2ObjectOpenHashMap<WorldServer, WorldIndex> worlds = new Reference2ObjectOpenHashMap<>();
        final ArrayDeque<Work> work = new ArrayDeque<>();
        long tick;
        long nextId;
        int remainingBytes;
        int remainingPackets;

        Session(MinecraftServer server) {
            this.server = server;
        }

        void tick(long allowance, long started) {
            tick = Math.incrementExact(tick);
            remainingBytes = BYTES_PER_TICK;
            remainingPackets = PACKETS_PER_TICK;
            int waiting = 0;
            while (!work.isEmpty() && waiting < work.size() && System.nanoTime() - started < allowance) {
                var next = work.removeFirst();
                switch (next.advance()) {
                    case DONE -> waiting = 0;
                    case CONTINUE -> {
                        work.addLast(next);
                        waiting = 0;
                    }
                    case WAIT -> {
                        work.addLast(next);
                        waiting++;
                    }
                }
            }
        }

        boolean send(EntityPlayerMP player, ConstructionEffectPayload payload) {
            return send(player, payload, null);
        }

        boolean send(EntityPlayerMP player, ConstructionEffectPayload payload, @Nullable End after) {
            int bytes = ConstructionEffectPayload.bytes(payload) + 1;
            int packets = 1;
            if (after != null) {
                bytes += ConstructionEffectPayload.bytes(after) + 1;
                packets++;
            }
            if (packets > remainingPackets || bytes > remainingBytes) return false;
            remainingPackets -= packets;
            remainingBytes -= bytes;
            InitNetwork.sendConstructionEffect(player, payload);
            if (after != null) InitNetwork.sendConstructionEffect(player, after);
            return true;
        }
    }

    private record WorldIndex(WorldServer world, Long2ObjectOpenHashMap<ObjectOpenHashSet<Sector>> chunks) {
        WorldIndex(WorldServer world) {
            this(world, new Long2ObjectOpenHashMap<>());
        }
    }

    private record SectorKey(Section section, int quadrant) {
        static SectorKey at(BlockPos position) {
            return new SectorKey(Section.at(position), Section.quadrant(position));
        }
    }

    public static final class Effect {
        private final Session session;
        private final WorldIndex index;
        private final EntityPlayerMP owner;
        private final NetHandlerPlayServer ownerConnection;
        private final long id;
        private final long anchor;
        private final boolean dismantle;
        private final int dimension;
        private final ConstructionArea.Builder areaBuilder = new ConstructionArea.Builder();
        private @Nullable ConstructionArea area;
        private final Object2ObjectLinkedOpenHashMap<SectorKey, Sector> sectors = new Object2ObjectLinkedOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<EntityPlayerMP, Audience> audience = new Reference2ObjectOpenHashMap<>();
        private @Nullable Iterator<Sector> publishing;
        private long startTick = -1;
        private long lastInitialSend;
        private @Nullable End ending;
        private boolean endReady;
        private boolean retiring;
        private int initialReplays;
        private int pendingDeltas;
        private int missing;
        private int errors;
        private int unloaded;
        private boolean paused;
        private boolean hudQueued;

        private Effect(Session session, WorldIndex index, EntityPlayerMP owner, long anchor, boolean dismantle) {
            this.session = session;
            this.index = index;
            this.owner = owner;
            ownerConnection = owner.connection;
            id = session.nextId = Math.incrementExact(session.nextId);
            this.anchor = anchor;
            this.dismantle = dismantle;
            dimension = index.world.provider.getDimension();
        }

        public void includeInArea(BlockPos position) {
            if (startTick >= 0) throw new IllegalStateException("Cannot change a published construction area");
            areaBuilder.include(position);
        }

        public void add(BlockPos position) {
            if (startTick >= 0) throw new IllegalStateException("Cannot change a published construction plan");
            if (!inBounds(position)) return;
            var key = SectorKey.at(position);
            sectors.computeIfAbsent(key, _ -> new Sector(this, key)).cells.set(Section.index(position));
        }

        /** Starts projection publication after preflight and the builder screen closes. */
        public void start() {
            if (startTick >= 0 || !live()) throw new IllegalStateException("Cannot start this construction projection");
            area = areaBuilder.build();
            startTick = session.tick;
            lastInitialSend = startTick;
            publishing = sectors.values().iterator();
            queueHud();
        }

        public boolean hasConstructionWork() {
            return publishing != null || introComplete();
        }

        private boolean introComplete() {
            return area == null || initialReplays == 0 && session.tick - lastInitialSend >= ConstructionEffectPayload.INTRO_TICKS;
        }

        /** Index the first audience inside the task's time share, then allow the intro to play. */
        public boolean ready(BooleanSupplier available) {
            if (startTick < 0 || ending != null || !live()) return false;
            var queue = publishing;
            if (queue != null) {
                while (queue.hasNext() && available.getAsBoolean()) queue.next().attach();
                if (queue.hasNext()) return false;
                publishing = null;
            }
            return introComplete();
        }

        /** Only call when the authoritative block operation has actually committed. */
        public void committed(BlockPos position) {
            change(position, true);
        }

        public void exclude(BlockPos position) {
            change(position, false);
        }

        private void change(BlockPos position, boolean changed) {
            if (!live() || !inBounds(position)) return;
            var sector = sectors.get(SectorKey.at(position));
            if (sector != null) sector.change(Section.index(position), changed);
        }

        public boolean ownerWithinRenderDistance() {
            var range = area;
            return range != null && range.withinRenderDistance(owner.posX, owner.posY, owner.posZ);
        }

        public void status(int missing, int errors, int unloaded, boolean paused) {
            if (this.missing == missing && this.errors == errors && this.unloaded == unloaded && this.paused == paused) return;
            this.missing = missing;
            this.errors = errors;
            this.unloaded = unloaded;
            this.paused = paused;
            if (startTick >= 0) queueHud();
        }

        public void finish(boolean successful) {
            end(false, successful);
        }

        public void cancel() {
            end(true, false);
        }

        private void end(boolean cancelled, boolean successful) {
            if (startTick < 0 || ending != null || !live()) return;
            ending = new End(dimension, id, session.tick, cancelled, successful);
            if (!cancelled) queueHud();
            session.work.addLast(new CloseEffect(this));
        }

        private void queueHud() {
            var range = area;
            if (hudQueued || range == null) return;
            hudQueued = true;
            session.work.addLast(() -> {
                var end = ending;
                if (live() && (end == null || !end.cancelled()) && connected(owner, ownerConnection)) {
                    if (end != null && pendingDeltas != 0) return WorkResult.WAIT;
                    var message = new Hud(dimension, id, anchor, dismantle, startTick, session.tick, range, missing, errors, unloaded,
                        paused, end != null && end.successful());
                    if (!session.send(owner, message)) return WorkResult.WAIT;
                    recipient(owner, ownerConnection).hud = true;
                }
                hudQueued = false;
                return WorkResult.DONE;
            });
        }

        private boolean live() {
            return current == session && session.worlds.get(index.world) == index;
        }

        private boolean connected(EntityPlayerMP player, NetHandlerPlayServer connection) {
            return player.world == index.world && player.connection == connection && connection.netManager.isChannelOpen()
                && player.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) == player;
        }

        private Audience recipient(EntityPlayerMP player, NetHandlerPlayServer connection) {
            var recipient = audience.get(player);
            if (recipient != null && recipient.connection == connection) return recipient;
            recipient = new Audience(player, connection);
            audience.put(player, recipient);
            return recipient;
        }

        private boolean deliverEnd(Audience recipient, End end) {
            if (recipient.endSent || audience.get(recipient.player) != recipient || !connected(recipient.player, recipient.connection)) return true;
            if (!session.send(recipient.player, end)) return false;
            recipient.endSent = true;
            return true;
        }

        private void release(Audience recipient) {
            if (--recipient.sectors == 0 && !recipient.hud) audience.remove(recipient.player, recipient);
        }

        private boolean cancelled() {
            var end = ending;
            return end != null && end.cancelled();
        }

        private static boolean inBounds(BlockPos pos) {
            return pos.getY() >= 0 && pos.getY() < 256 && pos.getX() >= -30000000 && pos.getX() < 30000000
                && pos.getZ() >= -30000000 && pos.getZ() < 30000000;
        }
    }

    private static final class CloseEffect implements Work {
        private final Effect effect;
        private @Nullable ObjectArrayList<Audience> endingAudience;
        private @Nullable Iterator<Sector> retiringSectors;
        private int cursor;
        private long retireAfter;

        CloseEffect(Effect effect) {
            this.effect = effect;
        }

        @Override
        public WorkResult advance() {
            if (!effect.live()) return WorkResult.DONE;
            var end = effect.ending;
            if (end == null) throw new IllegalStateException("Construction effect has no terminal state");
            var players = endingAudience;
            if (players == null) {
                if (!end.cancelled() && (effect.pendingDeltas != 0 || effect.initialReplays != 0 || effect.hudQueued)) return WorkResult.WAIT;
                players = new ObjectArrayList<>(effect.audience.values());
                endingAudience = players;
                end = effect.ending = new End(end.dimension(), end.taskId(), effect.session.tick, end.cancelled(), end.successful());
                effect.endReady = true;
                retireAfter = end.serverTick() + (end.cancelled() ? 0 : ConstructionEffectPayload.FINISH_TICKS);
            }
            if (cursor < players.size()) {
                if (!effect.deliverEnd(players.get(cursor), end)) return WorkResult.WAIT;
                cursor++;
                return WorkResult.CONTINUE;
            }
            if (effect.session.tick < retireAfter) return WorkResult.WAIT;
            var sectors = retiringSectors;
            if (sectors == null) {
                effect.retiring = true;
                sectors = retiringSectors = effect.sectors.values().iterator();
            }
            if (!sectors.hasNext()) return WorkResult.DONE;
            sectors.next().detach();
            return WorkResult.CONTINUE;
        }
    }

    private static final class Sector {
        private final Effect effect;
        private final SectorKey key;
        private final long chunk;
        private final BitSet cells = new BitSet();
        private final BitSet committed = new BitSet();
        private final BitSet excluded = new BitSet();
        private final BitSet pendingCommit = new BitSet();
        private final BitSet pendingExclude = new BitSet();
        private final Reference2ObjectOpenHashMap<EntityPlayerMP, Viewer> viewers = new Reference2ObjectOpenHashMap<>();
        private int revision;
        private boolean deltaQueued;

        Sector(Effect effect, SectorKey key) {
            this.effect = effect;
            this.key = key;
            chunk = ChunkPos.asLong(key.section.chunkX(key.quadrant), key.section.chunkZ(key.quadrant));
        }

        void attach() {
            var index = effect.index;
            index.chunks.computeIfAbsent(chunk, _ -> new ObjectOpenHashSet<>()).add(this);
            var entry = index.world.getPlayerChunkMap().getEntry(key.section.chunkX(key.quadrant), key.section.chunkZ(key.quadrant));
            if (entry == null) return;
            for (var player : entry.getWatchingPlayers()) watch(player, true);
        }

        void detach() {
            var chunks = effect.index.chunks;
            var siblings = chunks.get(chunk);
            if (siblings != null && siblings.remove(this) && siblings.isEmpty()) chunks.remove(chunk);
            viewers.clear();
        }

        void watch(EntityPlayerMP player, boolean initial) {
            if (effect.retiring || effect.cancelled()) return;
            var view = viewers.get(player);
            if (view != null && view.connection == player.connection) return;
            view = new Viewer(player, player.connection);
            viewers.put(player, view);
            if (initial) effect.initialReplays++;
            effect.session.work.addLast(new Replay(this, view, initial));
        }

        void unwatch(EntityPlayerMP player) {
            var viewer = viewers.remove(player);
            if (viewer == null) return;
            var recipient = viewer.recipient;
            if (recipient == null) return;
            var message = new Forget(effect.dimension, effect.id, key.section, key.quadrant);
            effect.session.work.addLast(() -> {
                if (effect.live() && effect.connected(player, viewer.connection)) {
                    var nowWatching = viewers.get(player);
                    if ((nowWatching == null || nowWatching.recipient == null) && !effect.session.send(player, message)) return WorkResult.WAIT;
                }
                effect.release(recipient);
                return WorkResult.DONE;
            });
        }

        boolean watching(Viewer view) {
            return viewers.get(view.player) == view && effect.connected(view.player, view.connection)
                && effect.index.world.getPlayerChunkMap().isPlayerWatchingChunk(view.player, key.section.chunkX(key.quadrant), key.section.chunkZ(key.quadrant));
        }

        void change(int cell, boolean changed) {
            if (!cells.get(cell)) return;
            if (changed && (excluded.get(cell) || pendingExclude.get(cell)) || !changed && (committed.get(cell) || pendingCommit.get(cell)))
                throw new IllegalStateException("Conflicting construction outcome at " + key + " cell " + cell);
            if (effect.startTick < 0) {
                if (changed) throw new IllegalStateException("A construction committed before projection started");
                excluded.set(cell);
                return;
            }
            if (changed) pendingCommit.set(cell);
            else pendingExclude.set(cell);
            if (deltaQueued) return;
            deltaQueued = true;
            effect.pendingDeltas++;
            effect.session.work.addLast(new Delta(this));
        }

        Geometry geometry(boolean snapshot, BitSet added, BitSet removed) {
            return new Geometry(effect.dimension, effect.id, effect.anchor, effect.dismantle, effect.startTick, effect.session.tick,
                key.section, key.quadrant, snapshot ? Objects.requireNonNull(effect.area) : null, snapshot ? cells : new BitSet(), added, removed);
        }
    }

    private static final class Viewer {
        final EntityPlayerMP player;
        final NetHandlerPlayServer connection;
        int revision = -1;
        @Nullable Audience recipient;

        Viewer(EntityPlayerMP player, NetHandlerPlayServer connection) {
            this.player = player;
            this.connection = connection;
        }
    }

    private static final class Audience {
        final EntityPlayerMP player;
        final NetHandlerPlayServer connection;
        int sectors;
        boolean hud;
        boolean endSent;

        Audience(EntityPlayerMP player, NetHandlerPlayServer connection) {
            this.player = player;
            this.connection = connection;
        }
    }

    private record Replay(Sector sector, Viewer viewer, boolean initial) implements Work {
        @Override
        public WorkResult advance() {
            var effect = sector.effect;
            if (!effect.live() || effect.retiring || effect.cancelled() || !sector.watching(viewer)) return finish(false);
            var end = effect.endReady ? effect.ending : null;
            if (end != null && effect.session.tick - end.serverTick() >= ConstructionEffectPayload.FINISH_TICKS) return finish(false);
            var recipient = effect.audience.get(viewer.player);
            if (recipient != null && recipient.connection == viewer.connection && recipient.endSent) end = null;
            if (effect.session.remainingPackets < (end == null ? 1 : 2)) return WorkResult.WAIT;
            if (!effect.session.send(viewer.player, sector.geometry(true, sector.committed, sector.excluded), end)) return WorkResult.WAIT;
            viewer.revision = sector.revision;
            recipient = viewer.recipient = effect.recipient(viewer.player, viewer.connection);
            recipient.sectors++;
            if (end != null) recipient.endSent = true;
            return finish(true);
        }

        private WorkResult finish(boolean sent) {
            if (initial) {
                var effect = sector.effect;
                effect.initialReplays--;
                if (sent) effect.lastInitialSend = effect.session.tick;
            }
            return WorkResult.DONE;
        }
    }

    private static final class Delta implements Work {
        private final Sector sector;
        private @Nullable Geometry packet;
        private @Nullable ObjectArrayList<Viewer> audience;
        private int cursor;

        Delta(Sector sector) {
            this.sector = sector;
        }

        @Override
        public WorkResult advance() {
            var effect = sector.effect;
            if (!effect.live() || effect.cancelled()) return finish();
            var players = audience;
            if (players == null) {
                if (sector.pendingCommit.isEmpty() && sector.pendingExclude.isEmpty()) return finish();
                sector.revision++;
                sector.committed.or(sector.pendingCommit);
                sector.excluded.or(sector.pendingExclude);
                players = new ObjectArrayList<>(sector.viewers.values());
                audience = players;
                if (!players.isEmpty()) packet = sector.geometry(false, sector.pendingCommit, sector.pendingExclude);
                sector.pendingCommit.clear();
                sector.pendingExclude.clear();
                cursor = 0;
            }
            if (cursor < players.size()) {
                var viewer = players.get(cursor);
                if (viewer.revision >= 0 && viewer.revision < sector.revision && sector.watching(viewer)) {
                    var message = packet;
                    if (message == null) throw new IllegalStateException("Missing construction geometry delta");
                    if (!effect.session.send(viewer.player, message)) return WorkResult.WAIT;
                    viewer.revision = sector.revision;
                }
                cursor++;
                return WorkResult.CONTINUE;
            }
            packet = null;
            audience = null;
            if (sector.pendingCommit.isEmpty() && sector.pendingExclude.isEmpty()) return finish();
            return WorkResult.CONTINUE;
        }

        private WorkResult finish() {
            sector.deltaQueued = false;
            sector.effect.pendingDeltas--;
            return WorkResult.DONE;
        }
    }
}
