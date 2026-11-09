package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Objects;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import org.jetbrains.annotations.Nullable;

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

    private StructurePattern(Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate> owned, StructureBounds bounds) {
        blocks = Object2ObjectMaps.unmodifiable(owned);
        this.bounds = bounds;
    }

    public Map<BlockPos, StructurePredicate> blocks() { return blocks; }
    public StructureBounds bounds() { return bounds; }

    public StructurePattern transform(StructureTransform rotation) {
        return Objects.requireNonNull(beginTransform(rotation).run());
    }

    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains only its immutable pattern definition.
    public StructureOperation<StructurePattern> beginTransform(StructureTransform rotation) {
        Objects.requireNonNull(rotation);
        var iterator = blocks.entrySet().iterator();
        return new StructureOperation<>() {
            private final Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate> transformed = new Object2ObjectLinkedOpenHashMap<>();
            @Nullable private BlockPos position;
            @Nullable private StructureOperation<StructurePredicate> predicate;
            private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                while (predicate != null || iterator.hasNext()) {
                    if (predicate == null) {
                        if (!work.take()) return false;
                        var entry = iterator.next();
                        position = rotation.apply(entry.getKey());
                        predicate = entry.getValue().beginTransform(rotation);
                    }
                    if (!predicate.advance(work)) return false;
                    var actual = Objects.requireNonNull(position);
                    if (transformed.putIfAbsent(actual, Objects.requireNonNull(predicate.result())) != null)
                        throw new IllegalArgumentException("Duplicate transformed structure position: " + actual);
                    minX = Math.min(minX, actual.getX()); minY = Math.min(minY, actual.getY()); minZ = Math.min(minZ, actual.getZ());
                    maxX = Math.max(maxX, actual.getX()); maxY = Math.max(maxY, actual.getY()); maxZ = Math.max(maxZ, actual.getZ());
                    predicate = null;
                }
                if (!work.take()) return false;
                var actualBounds = transformed.isEmpty() ? StructureBounds.at(BlockPos.ORIGIN)
                    : new StructureBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
                finish(new StructurePattern(transformed, actualBounds));
                return true;
            }
        };
    }

    public void collectReads(StructureReadPlan.Builder plan, BlockPos origin, BlockPos controller) {
        beginCollectReads(plan, origin, controller).run(); }

    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains only its immutable pattern definition.
    public StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos origin, BlockPos controller) {
        Objects.requireNonNull(plan); Objects.requireNonNull(origin); Objects.requireNonNull(controller);
        var iterator = blocks.entrySet().iterator();
        return new StructureOperation<>() {
            private boolean area;
            @Nullable private StructureOperation<Void> predicate;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                if (!area) {
                    if (!work.take()) return false;
                    plan.area(at(origin));
                    area = true;
                }
                while (predicate != null || iterator.hasNext()) {
                    if (predicate == null) {
                        if (!work.take()) return false;
                        var entry = iterator.next();
                        var position = add(origin, entry.getKey());
                        if (position.equals(controller)) continue;
                        predicate = entry.getValue().beginCollectReads(plan, position);
                    }
                    if (!predicate.advance(work)) return false;
                    predicate = null;
                }
                finish(null);
                return true;
            }
        };
    }

    public boolean matches(StructureSnapshot snapshot, BlockPos origin, BlockPos controller) {
        return beginMatch(snapshot, origin, controller).run() == StructurePredicateResult.MATCHED; }

    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains only its immutable pattern definition.
    public StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos origin, BlockPos controller) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(origin); Objects.requireNonNull(controller);
        var iterator = blocks.entrySet().iterator();
        return new StructureOperation<>() {
            private boolean area;
            @Nullable private StructureOperation<StructurePredicateResult> predicate;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                if (!area) {
                    if (!work.take()) return false;
                    if (!snapshot.isAreaLoaded(at(origin))) {
                        finish(StructurePredicateResult.UNAVAILABLE);
                        return true;
                    }
                    area = true;
                }
                while (predicate != null || iterator.hasNext()) {
                    if (predicate == null) {
                        if (!work.take()) return false;
                        var entry = iterator.next();
                        var position = add(origin, entry.getKey());
                        if (position.equals(controller)) continue;
                        predicate = entry.getValue().beginMatch(snapshot, position);
                    }
                    if (!predicate.advance(work)) return false;
                    var outcome = Objects.requireNonNull(predicate.result());
                    if (outcome != StructurePredicateResult.MATCHED) {
                        finish(outcome);
                        return true;
                    }
                    predicate = null;
                }
                finish(StructurePredicateResult.MATCHED);
                return true;
            }
        };
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
