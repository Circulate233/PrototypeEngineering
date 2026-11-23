package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.structure.tool.effect.ConstructionArea;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.RegionTime;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.util.math.AxisAlignedBB;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.HEIGHT;
import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.WIDTH;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.FINAL_WIRE_TICKS;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.LAYERS_PER_SECTION;
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

    ConstructionAnimation(boolean dismantle, long startTick, int wireTicks, ConstructionArea area) {
        this.dismantle = dismantle;
        this.startTick = startTick;
        this.wireTicks = wireTicks;
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
        for (int i = 0; i < LAYERS_PER_SECTION; i++) {
            int from = i * SLICE_CELLS, to = from + SLICE_CELLS;
            if (packet.snapshot() || !packet.excluded().isEmpty()) {
                apply(x, y + i, z, packet.snapshot() ? packet.cells().get(from, to) : null,
                    packet.snapshot() ? packet.fluids().get(from, to) : null, packet.excluded().get(from, to));
            }
        }
        for (var time : packet.regions()) {
            boolean fluid = time.region() >= LAYERS_PER_SECTION;
            int layer = fluid ? time.region() - LAYERS_PER_SECTION : time.region();
            var slice = slices.get(key(x, y + layer, z));
            if (slice != null) (fluid ? slice.fluid : slice.solid).apply(time, packet.serverTick(), clientTick, packet.snapshot(), finishAt);
        }
    }

    void metrics(int missing, int wrong, int unloaded, boolean paused) {
        if (this.missing != missing || this.wrong != wrong || this.unloaded != unloaded || this.paused != paused) hudText = null;
        this.missing = missing;
        this.wrong = wrong;
        this.unloaded = unloaded;
        this.paused = paused;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | ((long) y & 0xfff);
    }

    static int index(int x, int y, int z) {
        return x | z << 4 | y << 8;
    }

    void apply(int x, int y, int z, @Nullable BitSet snapshot, @Nullable BitSet fluids, BitSet excluded) {
        long key = key(x, y, z);
        var slice = slices.get(key);
        BitSet cells;
        BitSet liquid;
        if (snapshot == null) {
            if (slice == null || excluded.isEmpty()) return;
            cells = (BitSet) slice.cells.clone();
            liquid = (BitSet) slice.fluids.clone();
        } else {
            cells = snapshot;
            liquid = fluids == null ? new BitSet() : fluids;
        }
        cells.andNot(excluded);
        liquid.and(cells);
        if (cells.isEmpty()) {
            forget(x, y, z);
            return;
        }
        var changed = (BitSet) cells.clone();
        if (slice != null) {
            changed.xor(slice.cells);
            var materialChanges = (BitSet) liquid.clone();
            materialChanges.xor(slice.fluids);
            changed.or(materialChanges);
        }
        if (changed.isEmpty()) return;
        if (slice == null) {
            slice = new Slice(this, x, y, z);
            slices.put(key, slice);
        }
        slice.cells = cells;
        slice.fluids = liquid;
        slice.hasSolid = liquid.cardinality() < cells.cardinality();
        slice.invalidate();
        invalidateNeighbors(x, y, z, changed);
    }

    void forget(int x, int y, int z) {
        var removed = slices.remove(key(x, y, z));
        if (removed == null) return;
        removed.dispose();
        invalidateNeighbors(x, y, z, removed.cells);
    }

    void forgetChunk(int x, int z) {
        var min = area.min();
        var max = area.max();
        if (slices.isEmpty() || x < min.getX() >> 4 || x > max.getX() >> 4 || z < min.getZ() >> 4 || z > max.getZ() >> 4)
            return;
        for (int y = min.getY() >> 3; y <= max.getY() >> 3; y++) forget(x, y, z);
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
            var adjacent = slices.get(key(x + neighbor % 3 - 1, y + neighbor / 9 - 1, z + neighbor / 3 % 3 - 1));
            if (adjacent != null) adjacent.invalidate();
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
    }

    static final class Phase {
        private long activeAt = UNSET;
        private long settledAt = UNSET;

        void apply(RegionTime time, long serverTick, long clientTick, boolean snapshot, long finishAt) {
            if (activeAt == UNSET && time.activatedTick() >= 0) activeAt = clientTick - (serverTick - time.activatedTick());
            if (settledAt == UNSET && time.settledTick() >= 0) {
                settledAt = snapshot ? clientTick - (serverTick - time.settledTick()) : clientTick;
                if (finishAt != UNSET) settledAt = Math.min(settledAt, finishAt);
            }
        }

        double remaining(double now) {
            return settledAt == UNSET ? 1 : 1 - smooth(now - settledAt, REGION_FADE_TICKS);
        }
    }

    static final class Slice {
        final ConstructionAnimation task;
        final int x, y, z;
        final AxisAlignedBB view;
        BitSet cells = new BitSet();
        BitSet fluids = new BitSet();
        boolean hasSolid;
        int version;
        int wireVersion = -1;
        @Nullable ConstructionMeshes.Mesh wire;
        final Phase solid = new Phase();
        final Phase fluid = new Phase();
        final ConstructionMeshes.Part solidMesh = new ConstructionMeshes.Part();
        final ConstructionMeshes.Part fluidMesh = new ConstructionMeshes.Part();
        int seenFrame;
        boolean prepareWire;
        boolean prepareShell;
        double wireOpacity;

        Slice(ConstructionAnimation task, int x, int y, int z) {
            this.task = task;
            this.x = x;
            this.y = y;
            this.z = z;
            int minX = x * WIDTH, minY = y * HEIGHT, minZ = z * WIDTH;
            view = new AxisAlignedBB(minX, minY, minZ, minX + WIDTH, minY + HEIGHT, minZ + WIDTH);
        }

        byte cell(int x, int y, int z) {
            Slice slice = this;
            if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT || z < 0 || z >= WIDTH) {
                slice = task.slices.get(key(this.x + (x >> 4), this.y + (y >> 3), this.z + (z >> 4)));
                if (slice == null) return 0;
            }
            int index = index(x & 15, y & 7, z & 15);
            if (!slice.cells.get(index)) return 0;
            return slice.fluids.get(index) ? ConstructionSurface.FLUID : ConstructionSurface.SOLID;
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
