package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Objects;

/** Immutable sparse PM pattern. Loaded-area checks include gaps between its min/max positions. */
public final class StructurePattern {
    private final Map<BlockPos, StructurePredicate> blocks;
    private final StructureBounds bounds;

    public StructurePattern(Map<BlockPos, StructurePredicate> blocks) {
        var copy = new Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var entry : blocks.entrySet()) {
            var pos = Objects.requireNonNull(entry.getKey()).toImmutable();
            if (copy.putIfAbsent(pos, Objects.requireNonNull(entry.getValue())) != null) {
                throw new IllegalArgumentException("Duplicate structure position: " + pos);
            }
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
        }
        this.blocks = Object2ObjectMaps.unmodifiable(copy);
        bounds = copy.isEmpty() ? StructureBounds.at(BlockPos.ORIGIN)
            : new StructureBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    public Map<BlockPos, StructurePredicate> blocks() { return blocks; }
    public StructureBounds bounds() { return bounds; }

    public StructurePattern transform(StructureTransform rotation) {
        var transformed = new Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate>();
        blocks.forEach((position, predicate) -> transformed.put(rotation.apply(position), predicate.transform(rotation)));
        return new StructurePattern(transformed);
    }

    public void collectReads(StructureReadPlan.Builder plan, BlockPos origin, BlockPos controller) {
        plan.area(at(origin));
        blocks.forEach((position, predicate) -> {
            var worldPos = add(origin, position);
            if (!worldPos.equals(controller)) { predicate.collectReads(plan, worldPos); }
        });
    }

    public boolean matches(StructureSnapshot snapshot, BlockPos origin, BlockPos controller) {
        if (!snapshot.isAreaLoaded(at(origin))) { return false; }
        for (var entry : blocks.entrySet()) {
            var worldPos = add(origin, entry.getKey());
            if (!worldPos.equals(controller) && !entry.getValue().matches(snapshot, worldPos)) { return false; }
        }
        return true;
    }

    public StructureBounds at(BlockPos origin) { return new StructureBounds(add(origin, bounds.min()), add(origin, bounds.max())); }

    /** Exact lattice arithmetic; invalid world/definition coordinates must not silently wrap. */
    public static BlockPos add(BlockPos first, BlockPos second) {
        return new BlockPos(Math.addExact(first.getX(), second.getX()), Math.addExact(first.getY(), second.getY()), Math.addExact(first.getZ(), second.getZ()));
    }

    public static BlockPos multiply(BlockPos vector, int factor) {
        return new BlockPos(Math.multiplyExact(vector.getX(), factor), Math.multiplyExact(vector.getY(), factor), Math.multiplyExact(vector.getZ(), factor));
    }
}
