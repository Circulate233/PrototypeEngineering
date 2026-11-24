package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.structure.tool.effect.ConstructionArea;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.RegionTime;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.AxisAlignedBB;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;

import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.HEIGHT;
import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.WIDTH;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.FINAL_WIRE_TICKS;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.LAYERS_PER_SECTION;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.MAX_LOGICAL_CHUNKS;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.REGION_FADE_TICKS;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.WHITE_TRANSITION_TICKS;

final class ConstructionAnimation {
    static final int SLICE_CELLS = WIDTH * HEIGHT * WIDTH;
    static final double MIN_OPACITY = .004;
    private static final int COMPLETION_HUD_TICKS = 60;
    private static final long UNSET = Long.MIN_VALUE;

    final boolean dismantle;
    final long startTick;
    final ConstructionArea area;
    final ConstructionMeshes.Arena arena;
    final Long2ObjectLinkedOpenHashMap<Slice> slices = new Long2ObjectLinkedOpenHashMap<>();
    private final Phase[] phases;
    private final Long2ObjectOpenHashMap<byte[]> occupancy = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ObjectArrayList<Slice>> spatialSlices = new Long2ObjectOpenHashMap<>();
    boolean owner;
    boolean hidden;
    boolean complete;
    boolean paused;
    int missing, wrong, unloaded;
    @Nullable String hudText;

    private long firstServerTick = UNSET;
    private long latestServerTick = UNSET;
    private long introAt = UNSET;
    private final int wireTicks;
    private long finishAt = UNSET;

    ConstructionAnimation(boolean dismantle, long startTick, int wireTicks, int chunkCount, ConstructionArea area) {
        this.dismantle = dismantle;
        this.startTick = startTick;
        this.wireTicks = wireTicks;
        phases = new Phase[chunkCount * 2];
        for (int i = 0; i < phases.length; i++) phases[i] = new Phase();
        this.area = area;
        arena = new ConstructionMeshes.Arena(area.min());
    }

    void sample(long serverTick) {
        if (firstServerTick == UNSET) firstServerTick = serverTick;
        latestServerTick = Math.max(latestServerTick, serverTick);
    }

    void finish(long serverTick, long clientTick) {
        if (finishAt == UNSET) finishAt = firstServerTick > serverTick ? clientTick - (latestServerTick - serverTick) : clientTick;
    }

    double finishAge(double clientTick) {
        return finishAt == UNSET ? -1 : Math.max(0, clientTick - finishAt);
    }

    double wireOpacity(double now) {
        if (dismantle) {
            double after = finishAge(now);
            return after < 0 ? 0 : .92 * smooth(after, WHITE_TRANSITION_TICKS)
                * (1 - smooth(after - WHITE_TRANSITION_TICKS, FINAL_WIRE_TICKS - WHITE_TRANSITION_TICKS));
        }
        return finishAt == UNSET && introAt != UNSET ? .92 * (1 - smooth(now - introAt - wireTicks, WHITE_TRANSITION_TICKS)) : 0;
    }

    boolean prepareOutline(double now) {
        return dismantle && finishAge(now) < WHITE_TRANSITION_TICKS;
    }

    boolean prepareShell(double now) {
        return !dismantle && finishAt == UNSET && introAt != UNSET && now <= introAt + wireTicks;
    }

    double shellOpacity(Phase region, double now) {
        if (dismantle) {
            if (region.activeAt == UNSET || finishAt != UNSET && region.settledAt == UNSET) return 0;
            return smooth(now - region.activeAt, WHITE_TRANSITION_TICKS) * region.remaining(now);
        }
        return smooth(now - introAt - wireTicks, WHITE_TRANSITION_TICKS) * region.remaining(now);
    }

    boolean expired(double now) {
        return finishAge(now) >= (owner ? COMPLETION_HUD_TICKS : FINAL_WIRE_TICKS);
    }

    private static double smooth(double age, int duration) {
        double value = Math.clamp(age / duration, 0, 1);
        return value * value * (3 - 2 * value);
    }

    void geometry(Geometry packet, long clientTick) {
        sample(packet.serverTick());
        if (packet.snapshot()) {
            long startedAt = clientTick - (packet.serverTick() - startTick);
            introAt = introAt == UNSET ? startedAt : Math.min(introAt, startedAt);
        }
        var section = packet.section();
        int x = section.chunkX(packet.quadrant()), y = section.y() * LAYERS_PER_SECTION, z = section.chunkZ(packet.quadrant());
        var cells = packet.cells();
        var fluids = packet.fluids();
        var excluded = packet.excluded();
        int chunkCount = phases.length / 2;
        if (packet.snapshot()) {
            var groups = packet.cellGroups();
            var selected = new BitSet[LAYERS_PER_SECTION * chunkCount];
            var liquid = new BitSet[selected.length];
            int ordinal = 0;
            for (int index = cells.nextSetBit(0); index >= 0; index = cells.nextSetBit(index + 1)) {
                int group = groups[ordinal++];
                if (group >= chunkCount) throw new IllegalArgumentException("Construction snapshot references an unknown chunk");
                int slice = index / SLICE_CELLS;
                int localCell = index % SLICE_CELLS;
                int offset = slice * chunkCount + group;
                if (selected[offset] == null) selected[offset] = new BitSet(SLICE_CELLS);
                selected[offset].set(localCell);
                if (fluids.get(index)) {
                    if (liquid[offset] == null) liquid[offset] = new BitSet(SLICE_CELLS);
                    liquid[offset].set(localCell);
                }
            }
            for (int slice = 0; slice < LAYERS_PER_SECTION; slice++) {
                int height = y + slice;
                long space = spaceKey(x, height, z);
                var changed = new BitSet();
                for (int group = 0; group < chunkCount; group++) {
                    int offset = slice * chunkCount + group;
                    var old = slices.get(key(x, height, z, group));
                    var selection = selected[offset];
                    if (selection == null) {
                        if (old != null) remove(old, changed);
                    } else update(x, height, z, group, old, selection, liquid[offset], changed);
                }
                refresh(x, height, z, space, changed);
            }
        } else if (!excluded.isEmpty()) {
            for (int slice = 0; slice < LAYERS_PER_SECTION; slice++) {
                int from = slice * SLICE_CELLS;
                var removed = excluded.get(from, from + SLICE_CELLS);
                int height = y + slice;
                long space = spaceKey(x, height, z);
                var siblings = spatialSlices.get(space);
                if (siblings == null || removed.isEmpty()) continue;
                var changed = new BitSet();
                for (int i = siblings.size() - 1; i >= 0; i--) {
                    var old = siblings.get(i);
                    if (!old.cells.intersects(removed)) continue;
                    var selection = (BitSet) old.cells.clone();
                    selection.andNot(removed);
                    if (selection.isEmpty()) remove(old, changed);
                    else {
                        var liquids = (BitSet) old.fluids.clone();
                        liquids.andNot(removed);
                        update(x, height, z, old.group, old, selection, liquids, changed);
                    }
                }
                refresh(x, height, z, space, changed);
            }
        }
        for (var time : packet.regions()) {
            int group = time.region() % MAX_LOGICAL_CHUNKS;
            if (group >= chunkCount) throw new IllegalArgumentException("Construction transition references an unknown chunk");
            int phase = time.region() >= MAX_LOGICAL_CHUNKS ? group + chunkCount : group;
            phases[phase].apply(time, packet.serverTick(), clientTick, finishAt);
        }
    }

    void metrics(int missing, int wrong, int unloaded, boolean paused) {
        if (this.missing != missing || this.wrong != wrong || this.unloaded != unloaded || this.paused != paused) hudText = null;
        this.missing = missing;
        this.wrong = wrong;
        this.unloaded = unloaded;
        this.paused = paused;
    }

    private static long spaceKey(int x, int y, int z) {
        return ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | ((long) y & 0xfff);
    }

    static long key(int x, int y, int z, int group) {
        return spaceKey(x, y, z) & ~0xfffL | ((long) y & 0x3f) << 6 | group & 0x3f;
    }

    static int index(int x, int y, int z) {
        return x | z << 4 | y << 8;
    }

    private void update(int x, int y, int z, int group, @Nullable Slice slice,
                        BitSet cells, @Nullable BitSet fluids, BitSet changed) {
        var liquid = fluids == null ? new BitSet() : fluids;
        if (slice == null) {
            slice = new Slice(this, x, y, z, group);
            slices.put(key(x, y, z, group), slice);
            var siblings = spatialSlices.computeIfAbsent(spaceKey(x, y, z), _ -> new ObjectArrayList<>());
            slice.ownsWire = siblings.isEmpty();
            siblings.add(slice);
            changed.or(cells);
        } else {
            if (cells.equals(slice.cells) && liquid.equals(slice.fluids)) return;
            var differences = (BitSet) cells.clone();
            differences.xor(slice.cells);
            changed.or(differences);
            differences = (BitSet) liquid.clone();
            differences.xor(slice.fluids);
            changed.or(differences);
        }
        slice.cells = cells;
        slice.fluids = liquid;
        slice.hasSolid = liquid.cardinality() < cells.cardinality();
    }

    private void refresh(int x, int y, int z, long space, BitSet changed) {
        if (changed.isEmpty()) return;
        rebuildOccupancy(space);
        invalidateNeighbors(x, y, z, changed);
    }

    private void remove(Slice removed, BitSet changed) {
        slices.remove(key(removed.x, removed.y, removed.z, removed.group));
        long spatialKey = spaceKey(removed.x, removed.y, removed.z);
        var siblings = spatialSlices.get(spatialKey);
        siblings.remove(removed);
        if (siblings.isEmpty()) spatialSlices.remove(spatialKey);
        else siblings.getFirst().ownsWire = true;
        removed.dispose();
        changed.or(removed.cells);
    }

    void forgetChunk(int x, int z) {
        var min = area.min();
        var max = area.max();
        if (slices.isEmpty() || x < min.getX() >> 4 || x > max.getX() >> 4 || z < min.getZ() >> 4 || z > max.getZ() >> 4)
            return;
        for (int y = min.getY() >> 3; y <= max.getY() >> 3; y++) {
            long space = spaceKey(x, y, z);
            var siblings = spatialSlices.get(space);
            if (siblings == null) continue;
            var changed = new BitSet();
            while (!siblings.isEmpty()) remove(siblings.getLast(), changed);
            refresh(x, y, z, space, changed);
        }
    }

    private void rebuildOccupancy(long key) {
        var siblings = spatialSlices.get(key);
        if (siblings == null) {
            occupancy.remove(key);
            return;
        }
        var cells = occupancy.computeIfAbsent(key, _ -> new byte[SLICE_CELLS]);
        Arrays.fill(cells, (byte) 0);
        for (var slice : siblings) {
            slice.invalidate();
            for (int index = slice.cells.nextSetBit(0); index >= 0; index = slice.cells.nextSetBit(index + 1)) {
                byte value = slice.fluids.get(index) ? ConstructionSurface.FLUID : ConstructionSurface.SOLID;
                cells[index] = ConstructionSurface.groupMaterial(slice.group, value);
            }
        }
    }

    byte cell(int sliceX, int sliceY, int sliceZ, int x, int y, int z) {
        var cells = occupancy.get(spaceKey(sliceX, sliceY, sliceZ));
        return cells == null ? 0 : cells[index(x, y, z)];
    }

    private void invalidateNeighbors(int x, int y, int z, BitSet changed) {
        int neighbors = 0;
        for (int cell = changed.nextSetBit(0); cell >= 0; cell = changed.nextSetBit(cell + 1)) {
            int dx = boundary(cell & 15, WIDTH);
            int dy = boundary(cell >> 8, HEIGHT);
            int dz = boundary(cell >> 4 & 15, WIDTH);
            if ((dx | dy | dz) == 0) continue;
            for (int a = Math.min(dx, 0); a <= Math.max(dx, 0); a++)
                for (int b = Math.min(dy, 0); b <= Math.max(dy, 0); b++)
                    for (int c = Math.min(dz, 0); c <= Math.max(dz, 0); c++)
                        neighbors |= 1 << ((b + 1) * 9 + (c + 1) * 3 + a + 1);
        }
        neighbors &= ~(1 << 13);
        while (neighbors != 0) {
            int neighbor = Integer.numberOfTrailingZeros(neighbors);
            neighbors &= neighbors - 1;
            var adjacent = spatialSlices.get(spaceKey(x + neighbor % 3 - 1, y + neighbor / 9 - 1, z + neighbor / 3 % 3 - 1));
            if (adjacent != null) for (var slice : adjacent) slice.invalidate();
        }
    }

    private static int boundary(int value, int bound) {
        if (value == 0) return -1;
        return value == bound - 1 ? 1 : 0;
    }

    void invalidateMeshes() {
        for (var slice : slices.values()) slice.dispose();
    }

    void dispose() {
        invalidateMeshes();
        slices.clear();
        spatialSlices.clear();
        occupancy.clear();
    }

    static final class Phase {
        private long activeAt = UNSET;
        private long settledAt = UNSET;

        void apply(RegionTime time, long serverTick, long clientTick, long finishAt) {
            if (activeAt == UNSET && time.activatedTick() >= 0) activeAt = clientTick - (serverTick - time.activatedTick());
            if (settledAt == UNSET && time.settledTick() >= 0) {
                settledAt = clientTick - (serverTick - time.settledTick());
                if (finishAt != UNSET) settledAt = Math.min(settledAt, finishAt);
            }
        }

        double remaining(double now) {
            return settledAt == UNSET ? 1 : 1 - smooth(now - settledAt, REGION_FADE_TICKS);
        }
    }

    static final class Slice {
        final ConstructionAnimation task;
        final int x, y, z, group;
        final AxisAlignedBB view;
        BitSet cells = new BitSet();
        BitSet fluids = new BitSet();
        boolean hasSolid;
        boolean ownsWire;
        int version;
        int wireVersion = -1;
        @Nullable ConstructionMeshes.Mesh wire;
        final Phase solid;
        final Phase fluid;
        final ConstructionMeshes.Part solidMesh = new ConstructionMeshes.Part();
        final ConstructionMeshes.Part fluidMesh = new ConstructionMeshes.Part();
        int seenFrame;
        boolean prepareWire;
        boolean prepareShell;
        double wireOpacity;

        Slice(ConstructionAnimation task, int x, int y, int z, int group) {
            this.task = task;
            this.x = x;
            this.y = y;
            this.z = z;
            this.group = group;
            solid = task.phases[group];
            fluid = task.phases[group + task.phases.length / 2];
            int minX = x * WIDTH, minY = y * HEIGHT, minZ = z * WIDTH;
            view = new AxisAlignedBB(minX, minY, minZ, minX + WIDTH, minY + HEIGHT, minZ + WIDTH);
        }

        byte cell(int x, int y, int z) {
            return task.cell(this.x + (x >> 4), this.y + (y >> 3), this.z + (z >> 4), x & 15, y & 7, z & 15);
        }

        void invalidate() {
            version++;
        }

        void dispose() {
            invalidate();
            if (wire != null) wire.delete();
            wire = null;
            solidMesh.delete();
            fluidMesh.delete();
        }
    }
}
