package com.circulation.prototype_eng.structure.tool.effect;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.network.InitNetwork;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.End;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Forget;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Hud;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.RegionTime;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Section;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Objects;

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
    public static void tick(MinecraftServer server) {
        var active = current;
        if (active == null || active.server != server) return;
        active.tick();
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
            if (watching) sector.watch(player);
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

        void tick() {
            long started = System.nanoTime();
            tick = Integer.toUnsignedLong(server.getTickCounter());
            remainingBytes = BYTES_PER_TICK;
            remainingPackets = PACKETS_PER_TICK;
            int waiting = 0;
            while (!work.isEmpty() && waiting < work.size() && System.nanoTime() - started < SYNC_NANOS) {
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

        static long packed(BlockPos position) {
            return ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4) << 3 | (position.getY() >> 5);
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
        private final Long2ObjectLinkedOpenHashMap<Sector> sectors = new Long2ObjectLinkedOpenHashMap<>();
        private final Int2ObjectOpenHashMap<RemovalLayer> removalLayers = new Int2ObjectOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<EntityPlayerMP, Audience> audience = new Reference2ObjectOpenHashMap<>();
        private final ObjectOpenHashSet<Sector> unsettled = new ObjectOpenHashSet<>();
        private boolean publishing;
        private long startTick = -1;
        private int cellCount;
        private int wireTicks;
        private @Nullable End ending;
        private boolean endReady;
        private boolean retiring;
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

        public void add(BlockPos position, boolean fluid) {
            if (startTick >= 0) throw new IllegalStateException("Cannot change a published construction plan");
            if (!inBounds(position)) return;
            long key = SectorKey.packed(position);
            var sector = sectors.get(key);
            if (sector == null) {
                sector = new Sector(this, SectorKey.at(position));
                sectors.put(key, sector);
            }
            int cell = Section.index(position);
            if (!sector.cells.get(cell)) {
                sector.add(cell, fluid);
                cellCount++;
            }
        }

        /** Starts projection publication after preflight and the builder screen closes. */
        public void start() {
            if (startTick >= 0 || !live()) throw new IllegalStateException("Cannot start this construction projection");
            area = areaBuilder.build();
            startTick = currentTick();
            wireTicks = ConstructionEffectPayload.wireTicks(cellCount);
            queueHud();
            if (!sectors.isEmpty()) {
                publishing = true;
                session.work.addLast(new PublishEffect(this, sectors.values().iterator()));
            }
        }

        private boolean introComplete() {
            return cellCount == 0 || currentTick() - startTick >= (dismantle ? 0 : wireTicks + ConstructionEffectPayload.WHITE_TRANSITION_TICKS);
        }

        public boolean ready() {
            return startTick >= 0 && ending == null && live() && introComplete();
        }

        public boolean canRemove(BlockPos position, boolean fluid) {
            var sector = sectors.get(SectorKey.packed(position));
            if (sector == null) return true;
            int region = ConstructionEffectPayload.region(Section.index(position), fluid);
            return sector.canRemove(region);
        }

        private RemovalLayer addRemovalRegion(Sector sector, int region) {
            int key = sector.key.section.y() * ConstructionEffectPayload.REGION_COUNT + region;
            var layer = removalLayers.computeIfAbsent(key, _ -> new RemovalLayer(this, region));
            layer.sectors.add(sector);
            return layer;
        }

        public boolean resolved(BlockPos position, boolean fluid, boolean changed) {
            if (!inBounds(position)) return false;
            var sector = sectors.get(SectorKey.packed(position));
            if (sector == null) return false;
            int cell = Section.index(position);
            if (!sector.cells.get(cell)) return false;
            return sector.resolve(cell, ConstructionEffectPayload.region(cell, fluid), changed);
        }

        /** Called after this task's lighting and neighbor notifications have been processed. */
        public void flushSettled() {
            long tick = currentTick();
            for (var sector : unsettled) sector.settle(tick);
            unsettled.clear();
        }

        private long currentTick() {
            return Integer.toUnsignedLong(session.server.getTickCounter());
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
            ending = new End(dimension, id, currentTick(), cancelled, successful);
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

    private record PublishEffect(Effect effect, Iterator<Sector> sectors) implements Work {
        @Override
        public WorkResult advance() {
            if (effect.live() && !effect.cancelled()) {
                sectors.next().attach();
                if (sectors.hasNext()) return WorkResult.CONTINUE;
            }
            effect.publishing = false;
            return WorkResult.DONE;
        }
    }

    private static final class RemovalLayer implements Work {
        private final Effect effect;
        private final int index;
        private final ObjectArrayList<Sector> sectors = new ObjectArrayList<>();
        private int cursor;
        private boolean requested;

        RemovalLayer(Effect effect, int index) {
            this.effect = effect;
            this.index = index;
        }

        void request() {
            if (requested) return;
            requested = true;
            effect.session.work.addLast(this);
        }

        @Override
        public WorkResult advance() {
            if (!effect.live() || effect.cancelled()) return WorkResult.DONE;
            var sector = sectors.get(cursor);
            if (!sector.attached) return WorkResult.WAIT;
            var region = Objects.requireNonNull(sector.regions[index]);
            if (region.remaining > 0) {
                region.activating = true;
                sector.queueDelta();
            }
            return ++cursor == sectors.size() ? WorkResult.DONE : WorkResult.CONTINUE;
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
                if (!end.cancelled() && (effect.publishing || effect.pendingDeltas != 0 || !effect.unsettled.isEmpty() || effect.hudQueued))
                    return WorkResult.WAIT;
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
        private final BitSet fluids = new BitSet();
        private final BitSet excluded = new BitSet();
        private final BitSet pendingExclude = new BitSet();
        private final Region[] regions = new Region[ConstructionEffectPayload.REGION_COUNT];
        private final Reference2ObjectOpenHashMap<EntityPlayerMP, Viewer> viewers = new Reference2ObjectOpenHashMap<>();
        private int revision;
        private boolean deltaQueued;
        private boolean attached;

        Sector(Effect effect, SectorKey key) {
            this.effect = effect;
            this.key = key;
            chunk = ChunkPos.asLong(key.section.chunkX(key.quadrant), key.section.chunkZ(key.quadrant));
        }

        void attach() {
            attached = true;
            var index = effect.index;
            index.chunks.computeIfAbsent(chunk, _ -> new ObjectOpenHashSet<>()).add(this);
            var entry = index.world.getPlayerChunkMap().getEntry(key.section.chunkX(key.quadrant), key.section.chunkZ(key.quadrant));
            if (entry == null) return;
            for (var player : entry.getWatchingPlayers()) watch(player);
        }

        void detach() {
            var chunks = effect.index.chunks;
            var siblings = chunks.get(chunk);
            if (siblings != null && siblings.remove(this) && siblings.isEmpty()) chunks.remove(chunk);
            viewers.clear();
        }

        void watch(EntityPlayerMP player) {
            if (effect.retiring || effect.cancelled()) return;
            var view = viewers.get(player);
            if (view != null && view.connection == player.connection) return;
            view = new Viewer(player, player.connection);
            viewers.put(player, view);
            effect.session.work.addLast(new Replay(this, view));
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

        void add(int cell, boolean fluid) {
            cells.set(cell);
            if (fluid) fluids.set(cell);
            int index = ConstructionEffectPayload.region(cell, fluid);
            var region = regions[index];
            if (region == null) regions[index] = region = new Region(effect.dismantle ? effect.addRemovalRegion(this, index) : null);
            region.remaining++;
        }

        boolean canRemove(int index) {
            var region = regions[index];
            if (region == null) return true;
            if (region.removableTick < 0) {
                Objects.requireNonNull(region.removal).request();
                return false;
            }
            return effect.currentTick() >= region.removableTick;
        }

        boolean resolve(int cell, int index, boolean changed) {
            var region = Objects.requireNonNull(regions[index]);
            if (region.remaining <= 0) throw new IllegalStateException("Construction region already resolved at " + key);
            if (!changed) {
                pendingExclude.set(cell);
                queueDelta();
            }
            if (--region.remaining != 0) return false;
            effect.unsettled.add(this);
            return true;
        }

        void settle(long tick) {
            for (var region : regions) {
                if (region != null && region.remaining == 0 && region.settledTick < 0 && region.syncAfterTick < 0) {
                    region.syncAfterTick = tick;
                    queueDelta();
                }
            }
        }

        private void queueDelta() {
            if (deltaQueued) return;
            deltaQueued = true;
            effect.pendingDeltas++;
            effect.session.work.addLast(new Delta(this));
        }

        Geometry geometry(boolean snapshot, BitSet removed, int mask) {
            var times = new ArrayList<RegionTime>(Integer.bitCount(mask));
            for (int i = 0; i < regions.length; i++) {
                var region = regions[i];
                if ((mask & 1 << i) != 0 && region != null && (region.activatedTick >= 0 || region.settledTick >= 0))
                    times.add(new RegionTime(i, region.activatedTick, region.settledTick));
            }
            return new Geometry(effect.dimension, effect.id, effect.anchor, effect.dismantle, effect.startTick, effect.session.tick,
                effect.wireTicks, key.section, key.quadrant, snapshot ? Objects.requireNonNull(effect.area) : null,
                snapshot ? cells : new BitSet(), snapshot ? fluids : new BitSet(), removed, times);
        }
    }

    private static final class Region {
        final @Nullable RemovalLayer removal;
        int remaining;
        long activatedTick = -1;
        long removableTick = -1;
        long syncAfterTick = -1;
        long settledTick = -1;
        boolean activating;

        Region(@Nullable RemovalLayer removal) {
            this.removal = removal;
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

    private record Replay(Sector sector, Viewer viewer) implements Work {
        @Override
        public WorkResult advance() {
            var effect = sector.effect;
            if (!effect.live() || effect.retiring || effect.cancelled() || !sector.watching(viewer)) return WorkResult.DONE;
            var end = effect.endReady ? effect.ending : null;
            if (end != null && effect.session.tick - end.serverTick() >= ConstructionEffectPayload.FINISH_TICKS) return WorkResult.DONE;
            if (effect.session.remainingPackets < (end == null ? 1 : 2)) return WorkResult.WAIT;
            if (!effect.session.send(viewer.player, sector.geometry(true, sector.excluded, (1 << sector.regions.length) - 1), end)) return WorkResult.WAIT;
            viewer.revision = sector.revision;
            var recipient = viewer.recipient = effect.recipient(viewer.player, viewer.connection);
            recipient.sectors++;
            if (end != null) recipient.endSent = true;
            return WorkResult.DONE;
        }
    }

    private static final class Delta implements Work {
        private final Sector sector;
        private @Nullable Geometry packet;
        private @Nullable ObjectArrayList<Viewer> audience;
        private int cursor;
        private int activating;

        Delta(Sector sector) {
            this.sector = sector;
        }

        @Override
        public WorkResult advance() {
            var effect = sector.effect;
            if (!effect.live() || effect.cancelled()) return finish();
            var players = audience;
            if (players == null) {
                int mask = 0;
                boolean waiting = false;
                for (int i = 0; i < sector.regions.length; i++) {
                    var region = sector.regions[i];
                    if (region == null) continue;
                    if (region.activating) {
                        region.activating = false;
                        region.activatedTick = effect.session.tick;
                        activating |= 1 << i;
                        mask |= 1 << i;
                    }
                    if (region.syncAfterTick >= 0) {
                        if (effect.session.tick > region.syncAfterTick) {
                            region.syncAfterTick = -1;
                            region.settledTick = effect.session.tick;
                            mask |= 1 << i;
                        } else waiting = true;
                    }
                }
                if (mask == 0 && sector.pendingExclude.isEmpty()) return waiting ? WorkResult.WAIT : finish();
                sector.revision++;
                sector.excluded.or(sector.pendingExclude);
                players = new ObjectArrayList<>(sector.viewers.values());
                audience = players;
                if (!players.isEmpty()) packet = sector.geometry(false, sector.pendingExclude, mask);
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
            while (activating != 0) {
                int region = Integer.numberOfTrailingZeros(activating);
                Objects.requireNonNull(sector.regions[region]).removableTick = effect.session.tick + ConstructionEffectPayload.WHITE_TRANSITION_TICKS;
                activating &= activating - 1;
            }
            packet = null;
            audience = null;
            return WorkResult.CONTINUE;
        }

        private WorkResult finish() {
            sector.deltaQueued = false;
            sector.effect.pendingDeltas--;
            return WorkResult.DONE;
        }
    }
}
