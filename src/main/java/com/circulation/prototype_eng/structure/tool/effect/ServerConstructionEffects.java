package com.circulation.prototype_eng.structure.tool.effect;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.network.InitNetwork;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.End;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Hud;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Layout;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.RegionTime;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Section;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Status;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Visibility;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ByteOpenHashMap;
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
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
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

    /**
     * Publish after the previous native player chunk update, before starting new block changes.
     */
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

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        var active = current;
        if (active == null) return;
        var iterator = active.audiences.iterator();
        while (iterator.hasNext()) {
            var recipient = iterator.next();
            if (recipient.player != event.player || recipient.effect.dimension != event.fromDim) continue;
            iterator.remove();
            recipient.detach();
        }
    }

    private enum WorkResult {DONE, CONTINUE, WAIT}

    @FunctionalInterface
    private interface Work {
        WorkResult advance();
    }

    private static final class Session {
        final MinecraftServer server;
        final Reference2ObjectOpenHashMap<WorldServer, WorldIndex> worlds = new Reference2ObjectOpenHashMap<>();
        final ObjectOpenHashSet<Audience> audiences = new ObjectOpenHashSet<>();
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
            var viewers = audiences.iterator();
            while (viewers.hasNext()) {
                var recipient = viewers.next();
                var effect = recipient.effect;
                if (!effect.live() || !effect.connected(recipient.player, recipient.connection)) {
                    viewers.remove();
                    recipient.detach();
                } else if (!effect.retiring && !effect.cancelled() && !recipient.viewers.isEmpty())
                    recipient.updateDistance();
            }
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
        private final LongArrayList positions = new LongArrayList();
        private final Region[] regions = new Region[ConstructionEffectPayload.REGION_COUNT];
        private @Nullable ConstructionChunkPlan.Builder chunkPlanBuilder;
        private @Nullable ConstructionChunkPlan chunkPlan;
        private int assignedCells;
        private @Nullable Layout layout;
        private final Long2ObjectLinkedOpenHashMap<Sector> sectors = new Long2ObjectLinkedOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<EntityPlayerMP, Audience> audience = new Reference2ObjectOpenHashMap<>();
        private final ObjectOpenHashSet<Region> unsettled = new ObjectOpenHashSet<>();
        private boolean publishing;
        private long startTick = -1;
        private int cellCount;
        private int wireTicks;
        private long activated;
        private long removalRegions;
        private long settled;
        private @Nullable End ending;
        private boolean endReady;
        private boolean retiring;
        private int pendingDeltas;
        private Status status = new Status(0, 0, 0, false);
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

        public int includeInLayout(BlockPos position) {
            int index = positions.size();
            positions.add(position.toLong());
            return index;
        }

        public void add(BlockPos position, boolean fluid) {
            if (startTick >= 0 || chunkPlanBuilder != null || chunkPlan != null)
                throw new IllegalStateException("Cannot change a published construction plan");
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

        public boolean prepareChunkPlanStep() {
            if (startTick >= 0) throw new IllegalStateException("Cannot prepare a published construction plan");
            if (chunkPlan == null) {
                var builder = chunkPlanBuilder;
                if (builder == null) chunkPlanBuilder = builder = new ConstructionChunkPlan.Builder(positions);
                if (!builder.step()) return false;
                chunkPlan = builder.result();
                chunkPlanBuilder = null;
            }
            if (assignedCells < positions.size()) {
                int index = assignedCells++;
                var position = BlockPos.fromLong(positions.getLong(index));
                var sector = sectors.get(SectorKey.packed(position));
                int cell = Section.index(position);
                if (sector == null || !sector.cells.get(cell)) return false;
                int group = chunkPlan.chunkAt(index);
                sector.assign(cell, group);
                int stage = ConstructionEffectPayload.region(group, sector.fluids.get(cell));
                var region = regions[stage];
                if (region == null) regions[stage] = region = new Region(dismantle ? new RemovalLayer(this, stage) : null);
                region.remaining++;
                long bit = 1L << stage;
                if (dismantle) removalRegions |= bit;
                if ((sector.regionMask & bit) == 0) {
                    sector.regionMask |= bit;
                    region.sectors.add(sector);
                }
                return false;
            }
            return true;
        }

        public ConstructionChunkPlan chunkPlan() {
            return Objects.requireNonNull(chunkPlan, "Construction chunks have not been prepared");
        }

        public void start() {
            if (startTick >= 0 || !live()) throw new IllegalStateException("Cannot start this construction projection");
            var plan = chunkPlan();
            if (assignedCells != positions.size()) throw new IllegalStateException("Construction chunks are not assigned");
            var area = areaBuilder.build();
            startTick = currentTick();
            wireTicks = ConstructionEffectPayload.wireTicks(cellCount);
            if (area != null) layout = new Layout(anchor, dismantle, startTick, wireTicks, plan.size(), area);
            positions.clear();
            positions.trim();
            chunkPlan = null;
            queueHud();
            if (!sectors.isEmpty()) {
                publishing = true;
                session.work.addLast(new PublishEffect(this, sectors.values().iterator()));
                if (dismantle) requestRemoval(removalRegions);
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
            int index = ConstructionEffectPayload.region(sector.group(Section.index(position)), fluid);
            var region = regions[index];
            if (region == null) return true;
            if (region.removableTick >= 0) {
                if (currentTick() < region.removableTick) return false;
                if (!region.removalStarted) {
                    region.removalStarted = true;
                    long lowerRegions = (1L << index) - 1;
                    long samePhase = fluid ? -1L << ConstructionEffectPayload.MAX_LOGICAL_CHUNKS : -1L;
                    requestRemoval(removalRegions & lowerRegions & samePhase);
                }
                return true;
            }
            Objects.requireNonNull(region.removal).request();
            return false;
        }

        private void requestRemoval(long candidates) {
            if (candidates == 0) return;
            var region = Objects.requireNonNull(regions[Long.SIZE - 1 - Long.numberOfLeadingZeros(candidates)]);
            Objects.requireNonNull(region.removal).request();
        }

        public boolean resolved(BlockPos position, boolean fluid, boolean changed) {
            if (!inBounds(position)) return false;
            var sector = sectors.get(SectorKey.packed(position));
            if (sector == null) return false;
            int cell = Section.index(position);
            if (!sector.cells.get(cell)) return false;
            int index = ConstructionEffectPayload.region(sector.group(cell), fluid);
            var region = Objects.requireNonNull(regions[index]);
            if (region.remaining <= 0) throw new IllegalStateException("Construction group already resolved at " + position);
            if (!changed) {
                sector.pendingExclude.set(cell);
                sector.queueDelta();
            }
            if (--region.remaining != 0) return false;
            removalRegions &= ~(1L << index);
            unsettled.add(region);
            return true;
        }

        /** Called after this task's lighting and neighbor notifications have been processed. */
        public void flushSettled() {
            long tick = currentTick();
            for (var region : unsettled) {
                region.syncAfterTick = tick;
                for (var sector : region.sectors) sector.queueDelta();
            }
            unsettled.clear();
        }

        private long currentTick() {
            return Integer.toUnsignedLong(session.server.getTickCounter());
        }

        public boolean ownerWithinRenderDistance() {
            return withinRange(owner);
        }

        private boolean withinRange(EntityPlayerMP player) {
            var layout = this.layout;
            return layout != null && layout.area().withinRenderDistance(player.posX, player.posY, player.posZ);
        }

        public void status(int missing, int errors, int unloaded, boolean paused) {
            if (status.missing() == missing && status.errors() == errors && status.unloaded() == unloaded && status.paused() == paused)
                return;
            status = new Status(missing, errors, unloaded, paused);
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
            var info = layout;
            if (hudQueued || info == null) return;
            hudQueued = true;
            session.work.addLast(() -> {
                var end = ending;
                if (live() && (end == null || !end.cancelled()) && connected(owner, ownerConnection)) {
                    var recipient = recipient(owner);
                    if (!status.equals(recipient.lastStatus)) {
                        var message = new Hud(dimension, id, recipient.known ? null : info, status);
                        if (!session.send(owner, message)) return WorkResult.WAIT;
                        recipient.known = true;
                        recipient.lastStatus = status;
                    }
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

        private Audience recipient(EntityPlayerMP player) {
            var recipient = audience.get(player);
            if (recipient != null && recipient.connection == player.connection) return recipient;
            if (recipient != null) {
                session.audiences.remove(recipient);
                recipient.detach();
            }
            recipient = new Audience(this, player);
            audience.put(player, recipient);
            session.audiences.add(recipient);
            return recipient;
        }

        private boolean deliverEnd(Audience recipient, End end) {
            if (!recipient.known || recipient.endSent || audience.get(recipient.player) != recipient) return true;
            if (!session.send(recipient.player, end)) return false;
            recipient.endSent = true;
            return true;
        }

        private boolean cancelled() {
            var end = ending;
            return end != null && end.cancelled();
        }

        private boolean replayExpired() {
            var end = endReady ? ending : null;
            int ticks = dismantle ? ConstructionEffectPayload.FINAL_WIRE_TICKS : ConstructionEffectPayload.REGION_FADE_TICKS;
            return end != null && session.tick - end.serverTick() >= ticks;
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
            var region = Objects.requireNonNull(effect.regions[index]);
            var sector = region.sectors.get(cursor);
            if (!sector.attached) return WorkResult.WAIT;
            if (cursor == 0) {
                region.activatedTick = effect.session.tick;
                region.activationPendingSectors = region.sectors.size();
                effect.activated |= 1L << index;
            }
            sector.queueDelta();
            return ++cursor == region.sectors.size() ? WorkResult.DONE : WorkResult.CONTINUE;
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
                retireAfter = end.serverTick() + (end.cancelled() ? 0 : ConstructionEffectPayload.FINAL_WIRE_TICKS);
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
            if (!sectors.hasNext()) {
                effect.session.audiences.removeAll(effect.audience.values());
                effect.audience.clear();
                return WorkResult.DONE;
            }
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
        private byte firstGroup = -1;
        private @Nullable Short2ByteOpenHashMap otherGroups;
        private final Reference2ObjectOpenHashMap<EntityPlayerMP, Viewer> viewers = new Reference2ObjectOpenHashMap<>();
        private long regionMask;
        private long sentActivation;
        private long sentSettlement;
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
            var recipient = effect.recipient(player);
            if (viewers.containsKey(player)) return;
            var view = new Viewer(this, recipient);
            viewers.put(player, view);
            recipient.viewers.add(view);
            view.queueReplay();
        }

        void unwatch(EntityPlayerMP player) {
            var viewer = viewers.remove(player);
            if (viewer == null) return;
            var recipient = viewer.recipient;
            recipient.viewers.remove(viewer);
            if (viewer.revision >= 0) recipient.sentSectors--;
            recipient.discardIfUnused();
        }

        boolean watching(Viewer view) {
            return view.recipient.near && viewers.get(view.recipient.player) == view;
        }

        void add(int cell, boolean fluid) {
            cells.set(cell);
            if (fluid) fluids.set(cell);
        }

        void assign(int cell, int group) {
            if (firstGroup < 0) firstGroup = (byte) group;
            else if (group != firstGroup) {
                var others = otherGroups;
                if (others == null) {
                    otherGroups = others = new Short2ByteOpenHashMap();
                    others.defaultReturnValue(firstGroup);
                }
                others.put((short) cell, (byte) group);
            }
        }

        int group(int cell) {
            var others = otherGroups;
            return others == null ? firstGroup : others.get((short) cell);
        }

        private void queueDelta() {
            if (deltaQueued) return;
            deltaQueued = true;
            effect.pendingDeltas++;
            effect.session.work.addLast(new Delta(this));
        }

        @Nullable Geometry snapshot(Audience recipient, boolean replacing) {
            var visibleCells = (BitSet) cells.clone();
            visibleCells.andNot(excluded);
            if (visibleCells.isEmpty() && !replacing) return null;
            var visibleFluids = (BitSet) fluids.clone();
            visibleFluids.and(visibleCells);
            var groups = visibleCells.isEmpty() ? new byte[0]
                : otherGroups == null ? new byte[]{firstGroup} : ConstructionEffectPayload.packGroups(visibleCells, this::group);
            return new Geometry(effect.dimension, effect.id, effect.session.tick, key.section, key.quadrant, true,
                recipient.known ? null : Objects.requireNonNull(effect.layout), visibleCells, visibleFluids,
                groups, new BitSet(), missingTimes(recipient));
        }

        Geometry delta(BitSet removed, List<RegionTime> times) {
            return new Geometry(effect.dimension, effect.id, effect.session.tick, key.section, key.quadrant, false, null,
                new BitSet(), new BitSet(), new byte[0], removed, times);
        }

        private boolean hasMissingTimes(Audience recipient) {
            return (regionMask & (effect.activated & ~recipient.activated | effect.settled & ~recipient.settled)) != 0;
        }

        private List<RegionTime> missingTimes(Audience recipient) {
            return regionTimes(regionMask & effect.activated & ~recipient.activated, regionMask & effect.settled & ~recipient.settled);
        }

        private List<RegionTime> regionTimes(long activate, long settle) {
            long mask = activate | settle;
            if (mask == 0) return List.of();
            var times = new ArrayList<RegionTime>(Long.bitCount(mask));
            for (long remaining = mask; remaining != 0; remaining &= remaining - 1) {
                int i = Long.numberOfTrailingZeros(remaining);
                long flag = 1L << i;
                var region = Objects.requireNonNull(effect.regions[i]);
                long activatedTick = (activate & flag) == 0 ? -1 : region.activatedTick;
                long settledTick = (settle & flag) == 0 ? -1 : region.settledTick;
                if (activatedTick >= 0 || settledTick >= 0) times.add(new RegionTime(i, activatedTick, settledTick));
            }
            return times;
        }
    }

    private static final class Region {
        final @Nullable RemovalLayer removal;
        final ObjectArrayList<Sector> sectors = new ObjectArrayList<>();
        int remaining;
        long activatedTick = -1;
        long removableTick = -1;
        long syncAfterTick = -1;
        long settledTick = -1;

        void activationPublished(Effect effect) {
            if (activationPendingSectors <= 0) throw new IllegalStateException("Construction activation was published more than once");
            if (--activationPendingSectors == 0) removableTick = effect.session.tick + ConstructionEffectPayload.WHITE_TRANSITION_TICKS;
        }

        int activationPendingSectors;
        boolean removalStarted;

        Region(@Nullable RemovalLayer removal) {
            this.removal = removal;
        }
    }

    private static final class Viewer implements Work {
        final Sector sector;
        final Audience recipient;
        int revision = -1;
        boolean replayQueued;

        Viewer(Sector sector, Audience recipient) {
            this.sector = sector;
            this.recipient = recipient;
        }

        void queueReplay() {
            if (replayQueued || !recipient.near || revision == sector.revision && !sector.hasMissingTimes(recipient)) return;
            replayQueued = true;
            recipient.pendingReplays++;
            recipient.effect.session.work.addLast(this);
        }

        @Override
        public WorkResult advance() {
            var effect = recipient.effect;
            if (!effect.live() || effect.retiring || effect.cancelled() || effect.replayExpired() || !sector.watching(this)) return done();
            var end = effect.endReady ? effect.ending : null;
            var after = recipient.endSent ? null : end;
            if (effect.session.remainingPackets < (after == null ? 1 : 2)) return WorkResult.WAIT;
            Geometry update;
            if (revision == sector.revision) {
                var times = sector.missingTimes(recipient);
                if (times.isEmpty()) return done();
                update = sector.delta(new BitSet(), times);
            } else {
                update = sector.snapshot(recipient, revision >= 0);
                if (update == null) return done();
            }
            if (!effect.session.send(recipient.player, update, after)) return WorkResult.WAIT;
            recipient.recordTimes(update);
            if (revision < 0) recipient.sentSectors++;
            revision = sector.revision;
            recipient.known = true;
            if (after != null) recipient.endSent = true;
            return done();
        }

        private WorkResult done() {
            replayQueued = false;
            recipient.pendingReplays--;
            return WorkResult.DONE;
        }
    }

    private static final class Audience {
        final Effect effect;
        final EntityPlayerMP player;
        final NetHandlerPlayServer connection;
        final ObjectOpenHashSet<Viewer> viewers = new ObjectOpenHashSet<>();
        @Nullable Status lastStatus;
        long activated;
        long settled;
        int sentSectors;
        int pendingReplays;
        boolean near;
        boolean hidden;
        boolean visibilityQueued;
        boolean known;
        boolean endSent;

        Audience(Effect effect, EntityPlayerMP player) {
            this.effect = effect;
            this.player = player;
            connection = player.connection;
            near = effect.withinRange(player);
        }

        void recordTimes(Geometry packet) {
            for (var time : packet.regions()) {
                long bit = 1L << time.region();
                if (time.activatedTick() >= 0) activated |= bit;
                if (time.settledTick() >= 0) settled |= bit;
            }
        }

        void updateDistance() {
            boolean withinRange = effect.withinRange(player);
            if (near == withinRange) return;
            near = withinRange;
            if (near) for (var viewer : viewers) viewer.queueReplay();
            if (!visibilityQueued && (near ? hidden : !hidden && sentSectors != 0)) {
                visibilityQueued = true;
                effect.session.work.addLast(this::syncVisibility);
            }
        }

        private WorkResult syncVisibility() {
            if (effect.live() && !effect.cancelled() && !effect.retiring && effect.audience.get(player) == this
                && (near ? hidden && !effect.replayExpired() : !hidden && sentSectors != 0)) {
                if (near && pendingReplays != 0) return WorkResult.WAIT;
                if (!effect.session.send(player, new Visibility(effect.dimension, effect.id, near))) return WorkResult.WAIT;
                hidden = !near;
            }
            visibilityQueued = false;
            return WorkResult.DONE;
        }

        void discardIfUnused() {
            if (known || !viewers.isEmpty()) return;
            effect.session.audiences.remove(this);
            effect.audience.remove(player, this);
        }

        void detach() {
            for (var viewer : viewers) viewer.sector.viewers.remove(player, viewer);
            viewers.clear();
            effect.audience.remove(player, this);
        }
    }

    private static final class Delta implements Work {
        private final Sector sector;
        private BitSet removed = new BitSet();
        private @Nullable ObjectArrayList<Viewer> audience;
        private int cursor;
        private int baseRevision;
        private long activating;
        private long settled;

        Delta(Sector sector) {
            this.sector = sector;
        }

        @Override
        public WorkResult advance() {
            var effect = sector.effect;
            if (!effect.live() || effect.cancelled()) return finish();
            var players = audience;
            if (players == null) {
                boolean waiting = false;
                for (long mask = sector.regionMask; mask != 0; mask &= mask - 1) {
                    int index = Long.numberOfTrailingZeros(mask);
                    var region = Objects.requireNonNull(effect.regions[index]);
                    if (region.syncAfterTick >= 0 && region.settledTick < 0) {
                        if (effect.session.tick > region.syncAfterTick) {
                            region.syncAfterTick = -1;
                            region.settledTick = effect.session.tick;
                            effect.settled |= 1L << index;
                        } else waiting = true;
                    }
                }
                activating = 0;
                settled = 0;
                for (long mask = sector.regionMask; mask != 0; mask &= mask - 1) {
                    int index = Long.numberOfTrailingZeros(mask);
                    long bit = 1L << index;
                    var region = Objects.requireNonNull(effect.regions[index]);
                    if (region.activatedTick >= 0 && (sector.sentActivation & bit) == 0) activating |= bit;
                    if (region.settledTick >= 0 && (sector.sentSettlement & bit) == 0) settled |= bit;
                }
                if ((activating | settled) == 0 && sector.pendingExclude.isEmpty())
                    return waiting ? WorkResult.WAIT : finish();
                baseRevision = sector.revision;
                removed = (BitSet) sector.pendingExclude.clone();
                if (!removed.isEmpty()) {
                    sector.revision++;
                    sector.excluded.or(removed);
                }
                players = new ObjectArrayList<>(sector.viewers.values());
                audience = players;
                sector.pendingExclude.clear();
                cursor = 0;
            }
            if (cursor < players.size()) {
                var viewer = players.get(cursor);
                if (viewer.revision >= 0 && sector.watching(viewer)) {
                    if (viewer.revision < baseRevision || viewer.replayQueued) viewer.queueReplay();
                    else {
                        var recipient = viewer.recipient;
                        var times = sector.regionTimes(activating & ~recipient.activated, settled & ~recipient.settled);
                        if (!times.isEmpty() || viewer.revision != sector.revision) {
                            var message = sector.delta(viewer.revision == sector.revision ? new BitSet() : removed, times);
                            if (!effect.session.send(recipient.player, message)) return WorkResult.WAIT;
                            recipient.recordTimes(message);
                        }
                        viewer.revision = sector.revision;
                    }
                }
                cursor++;
                return WorkResult.CONTINUE;
            }
            for (long mask = activating; mask != 0; mask &= mask - 1) {
                int index = Long.numberOfTrailingZeros(mask);
                sector.sentActivation |= 1L << index;
                Objects.requireNonNull(effect.regions[index]).activationPublished(effect);
            }
            sector.sentSettlement |= settled;
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
