package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.structure.tool.effect.ConstructionArea;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.util.math.AxisAlignedBB;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.Objects;

final class ConstructionAnimation {
    static final int EDGE = 32;
    static final int COUNT = EDGE * EDGE * EDGE;
    static final int WHITE_WIRE_TICKS = 9;
    static final int WHITE_HOLD_TICKS = 5;
    static final int BUILD_REVEAL_TICKS = 55;
    static final int REMOVE_WIRE_TICKS = 10;
    static final int FINISH_COLOR_TICKS = 12;
    static final int PLAN_FILL = 1;
    static final int PLAN_WIRE = 2;
    static final int PENDING_FILL = 4;
    static final int CHANGED_FILL = 8;
    static final int ALL = PLAN_FILL | PLAN_WIRE | PENDING_FILL | CHANGED_FILL;

    final boolean dismantle;
    final long startTick;
    final ConstructionArea area;
    final Long2ObjectLinkedOpenHashMap<Section> sections = new Long2ObjectLinkedOpenHashMap<>();
    final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    @Nullable Bounds bounds;
    @Nullable ConstructionMeshes.Mesh frame;
    boolean updateBounds;
    boolean owner;
    boolean complete;
    boolean paused;
    private long sampleTick;
    private long sampleAt = -1;
    private long receivedTick = -1;
    private long observedFrom = -1;
    private long finishAt;
    private boolean finished;
    boolean success;
    int missing, wrong, unloaded;
    @Nullable String hudText;

    ConstructionAnimation(boolean dismantle, long startTick, ConstructionArea area) {
        this.dismantle = dismantle;
        this.startTick = startTick;
        this.area = area;
        sampleTick = startTick;
    }

    void sample(long serverTick, long clientTick) {
        if (serverTick > receivedTick) {
            if (sampleAt < 0) {
                observedFrom = serverTick;
                sampleTick = serverTick;
            } else sampleTick = Math.max(serverTick, sampleTick + clientTick - sampleAt);
            sampleAt = clientTick;
            receivedTick = serverTick;
        }
    }

    double age(double clientTick) {
        return sampleTick - startTick + Math.max(0.0, clientTick - sampleAt);
    }

    void finish(long serverTick, long clientTick, boolean successful) {
        if (finished) return;
        // A viewer whose first snapshot follows the end joins the current phase.
        // Viewers already watching play the full reveal when the end arrives.
        finishAt = observedFrom > serverTick ? clientTick - (receivedTick - serverTick) : clientTick;
        finished = true;
        success = successful;
    }

    double finishAge(double clientTick) {
        return finished ? Math.max(0, clientTick - finishAt) : -1;
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

    static int x(long key) {
        return (int) (key >> 38);
    }

    static int y(long key) {
        return (int) (key << 52 >> 52);
    }

    static int z(long key) {
        return (int) (key << 26 >> 38);
    }

    static int index(int x, int y, int z) {
        return x | z << 5 | y << 10;
    }

    static int localX(int cell) {
        return cell & 31;
    }

    static int localY(int cell) {
        return cell >> 10;
    }

    static int localZ(int cell) {
        return cell >> 5 & 31;
    }

    void markDirty(long key, boolean planChanged) {
        flag(key, ALL);
        updateBounds |= planChanged;
        int x = x(key), y = y(key), z = z(key);
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            flag(key(x + dx, y + dy, z + dz), ALL);
        }
    }

    void committed(long key) {
        flag(key, PENDING_FILL | CHANGED_FILL);
        int x = x(key), y = y(key), z = z(key);
        flag(key(x - 1, y, z), PENDING_FILL | CHANGED_FILL);
        flag(key(x + 1, y, z), PENDING_FILL | CHANGED_FILL);
        flag(key(x, y - 1, z), PENDING_FILL | CHANGED_FILL);
        flag(key(x, y + 1, z), PENDING_FILL | CHANGED_FILL);
        flag(key(x, y, z - 1), PENDING_FILL | CHANGED_FILL);
        flag(key(x, y, z + 1), PENDING_FILL | CHANGED_FILL);
    }

    private void flag(long key, int kinds) {
        var section = sections.get(key);
        if (section == null) dirty.remove(key);
        else {
            section.dirty |= kinds;
            dirty.add(key);
        }
    }

    boolean occupied(Section center, int x, int y, int z, MeshKind kind) {
        if ((x | y | z) >= 0 && (x | y | z) < EDGE)
            return center.occupied(index(x, y, z), kind);
        var other = sections.get(key(center.x + (x >> 5), center.y + (y >> 5), center.z + (z >> 5)));
        return other != null && other.occupied(index(x & 31, y & 31, z & 31), kind);
    }

    static final class Section {
        final int x, y, z;
        final AxisAlignedBB view;
        final BitSet plan = new BitSet(COUNT);
        final BitSet changed = new BitSet(COUNT);
        final BitSet excluded = new BitSet(COUNT);
        int dirty = ALL;
        @Nullable Bounds bounds;
        @Nullable ConstructionMeshes.Mesh planFill;
        @Nullable ConstructionMeshes.Mesh planWire;
        @Nullable ConstructionMeshes.Mesh pendingFill;
        @Nullable ConstructionMeshes.Mesh changedFill;

        Section(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            view = new AxisAlignedBB(x * EDGE, y * EDGE, z * EDGE, (x + 1) * EDGE, (y + 1) * EDGE, (z + 1) * EDGE).grow(0.005);
        }

        void apply(Geometry geometry) {
            int quadrant = geometry.quadrant();
            if (geometry.snapshot()) {
                clearQuadrant(quadrant);
                copy(geometry.cells(), plan, quadrant);
                bounds = Bounds.of(this);
            }
            copy(geometry.committed(), changed, quadrant);
            copy(geometry.excluded(), excluded, quadrant);
        }

        void forget(int quadrant) {
            clearQuadrant(quadrant);
            bounds = Bounds.of(this);
        }

        private void clearQuadrant(int quadrant) {
            int x = (quadrant & 1) << 4;
            int z = (quadrant >> 1) << 4;
            for (int y = 0; y < EDGE; y++) for (int dz = z; dz < z + 16; dz++) {
                int row = index(x, y, dz);
                plan.clear(row, row + 16);
                changed.clear(row, row + 16);
                excluded.clear(row, row + 16);
            }
        }

        private static void copy(BitSet cells, BitSet output, int quadrant) {
            for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1))
                output.set(Geometry.sectionIndex(quadrant, cell));
        }

        boolean occupied(int cell, MeshKind kind) {
            return switch (kind) {
                case PLAN -> plan.get(cell) && !excluded.get(cell);
                case PENDING -> plan.get(cell) && !changed.get(cell) && !excluded.get(cell);
                case CHANGED -> changed.get(cell);
            };
        }

        BitSet visit(MeshKind kind) {
            return kind == MeshKind.CHANGED ? changed : plan;
        }

        void dispose() {
            if (planFill != null) planFill.delete();
            if (planWire != null) planWire.delete();
            if (pendingFill != null) pendingFill.delete();
            if (changedFill != null) changedFill.delete();
            planFill = null;
            planWire = null;
            pendingFill = null;
            changedFill = null;
        }
    }

    void refreshBounds() {
        if (!updateBounds) return;
        var previous = bounds;
        bounds = null;
        for (var section : sections.values()) {
            if (section.bounds != null) bounds = bounds == null ? section.bounds : bounds.union(section.bounds);
        }
        if (!Objects.equals(previous, bounds) && frame != null) {
            frame.delete();
            frame = null;
        }
        updateBounds = false;
    }

    boolean expired(double tick) {
        return finishAge(tick) >= ConstructionEffectPayload.FINISH_TICKS;
    }

    void dispose() {
        if (frame != null) frame.delete();
        for (var section : sections.values()) section.dispose();
        sections.clear();
        dirty.clear();
    }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static @Nullable Bounds of(Section section) {
            if (section.plan.isEmpty()) return null;
            int minX = EDGE, minY = EDGE, minZ = EDGE, maxX = -1, maxY = -1, maxZ = -1;
            for (int cell = section.plan.nextSetBit(0); cell >= 0; cell = section.plan.nextSetBit(cell + 1)) {
                int x = localX(cell), y = localY(cell), z = localZ(cell);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            int x = section.x * EDGE, y = section.y * EDGE, z = section.z * EDGE;
            return new Bounds(x + minX, y + minY, z + minZ, x + maxX + 1, y + maxY + 1, z + maxZ + 1);
        }

        Bounds union(Bounds other) {
            return new Bounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }
    }

    enum MeshKind {
        PLAN, PENDING, CHANGED
    }
}
