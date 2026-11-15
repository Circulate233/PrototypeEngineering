package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;


/**
 * Immutable sparse PM pattern. Loaded-area checks include gaps between its min/max positions.
 * The supplied position map is borrowed for the pattern's lifetime and must not be modified.
 */
public final class StructurePattern {
    private final BlockPos2ObjectMap<StructurePredicate> blocks;
    private final StructureBounds bounds;
    private final boolean planReusable;
    private volatile BlockPos2ObjectMap<StructureDisplay> displays;

    public StructurePattern(BlockPos2ObjectMap<StructurePredicate> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean reusable = true;
        for (var entries = blocks.fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            long pos = entry.getLongKey();
            int x = BlockPos2ObjectMap.unpackX(pos), y = BlockPos2ObjectMap.unpackY(pos), z = BlockPos2ObjectMap.unpackZ(pos);
            var predicate = entry.getValue();
            reusable = reusable && predicate.planReusable();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        this.blocks = blocks;
        planReusable = reusable;
        bounds = blocks.isEmpty() ? StructureBounds.at(BlockPos.ORIGIN)
            : new StructureBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    private StructurePattern(BlockPos2ObjectMap<StructurePredicate> owned, StructureBounds bounds, boolean planReusable) {
        blocks = owned;
        this.bounds = bounds;
        this.planReusable = planReusable;
    }

    /**
     * Exact lattice arithmetic; invalid world/definition coordinates must not silently wrap.
     */
    public static BlockPos add(BlockPos first, BlockPos second) {
        return new BlockPos(Math.addExact(first.getX(), second.getX()), Math.addExact(first.getY(), second.getY()), Math.addExact(first.getZ(), second.getZ()));
    }

    /**
     * Adds an already packed relative position without a temporary decoded BlockPos.
     */
    public static BlockPos add(BlockPos origin, long offset) {
        return new BlockPos(Math.addExact(origin.getX(), BlockPos2ObjectMap.unpackX(offset)),
            Math.addExact(origin.getY(), BlockPos2ObjectMap.unpackY(offset)), Math.addExact(origin.getZ(), BlockPos2ObjectMap.unpackZ(offset)));
    }

    public boolean planReusable() {
        return planReusable;
    }

    public BlockPos2ObjectMap<StructurePredicate> blocks() {
        return blocks;
    }

    /**
     * Lazy freezing is deliberately separate from matching and cooperative transformation.
     */
    public BlockPos2ObjectMap<StructureDisplay> displays() {
        var result = displays;
        if (result != null) return result;
        synchronized (this) {
            result = displays;
            if (result == null) {
                var copy = new BlockPos2ObjectMap<StructureDisplay>();
                for (var entries = blocks.fastIterator(); entries.hasNext(); ) {
                    var entry = entries.next();
                    var display = entry.getValue().display();
                    if (display != null) copy.put(entry.getLongKey(), display);
                }
                result = copy;
                displays = result;
            }
        }
        return result;
    }

    public StructureBounds bounds() {
        return bounds;
    }

    public StructurePattern transform(StructureTransform rotation) {
        return beginTransform(rotation).run();
    }

    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains only its immutable pattern definition.
    public StructureOperation<StructurePattern> beginTransform(StructureTransform rotation) {
        var iterator = blocks.fastIterator();
        return new StructureOperation<>() {
            private final BlockPos2ObjectMap<StructurePredicate> transformed = new BlockPos2ObjectMap<>(blocks.size());
            @Nullable
            private BlockPos position;
            @Nullable
            private StructureOperation<StructurePredicate> predicate;
            private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            private boolean reusable = planReusable;

            @Override
            @SuppressWarnings("DataFlowIssue")
            // The pending predicate is created together with its transformed position.
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                while (predicate != null || iterator.hasNext()) {
                    if (predicate == null) {
                        if (!work.take()) return false;
                        var entry = iterator.next();
                        position = rotation.apply(entry.getLongKey());
                        predicate = entry.getValue().beginTransform(rotation);
                    }
                    if (!predicate.advance(work)) return false;
                    var actual = position;
                    if (transformed.putIfAbsent(actual, predicate.result()) != null)
                        throw new IllegalArgumentException("Duplicate transformed structure position: " + actual);
                    reusable = reusable && predicate.result().planReusable();
                    minX = Math.min(minX, actual.getX());
                    minY = Math.min(minY, actual.getY());
                    minZ = Math.min(minZ, actual.getZ());
                    maxX = Math.max(maxX, actual.getX());
                    maxY = Math.max(maxY, actual.getY());
                    maxZ = Math.max(maxZ, actual.getZ());
                    predicate = null;
                }
                if (!work.take()) return false;
                var actualBounds = transformed.isEmpty() ? StructureBounds.at(BlockPos.ORIGIN)
                    : new StructureBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
                finish(new StructurePattern(transformed, actualBounds, reusable));
                return true;
            }
        };
    }

    public void collectReads(StructureReadPlan.Builder plan, BlockPos origin, BlockPos controller) {
        beginCollectReads(plan, origin, controller).run();
    }

    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains only its immutable pattern definition.
    public StructureOperation<@Nullable Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos origin, BlockPos controller) {
        var iterator = blocks.fastIterator();
        return new StructureOperation<>() {
            private boolean area;
            @Nullable
            private StructureOperation<@Nullable Void> predicate;

            @Override
            public boolean advance(RecipeWorkBudget work) {
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
                        var position = add(origin, entry.getLongKey());
                        if (position.equals(controller)) continue;
                        predicate = entry.getValue().beginCollectReads(plan, position, controller);
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
        return beginMatch(snapshot, origin, controller).run() == StructurePredicateResult.MATCHED;
    }

    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains only its immutable pattern definition.
    public StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos origin, BlockPos controller) {
        var iterator = blocks.fastIterator();
        return new StructureOperation<>() {
            private boolean area;
            @Nullable
            private StructureOperation<StructurePredicateResult> predicate;

            @Override
            public boolean advance(RecipeWorkBudget work) {
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
                        var position = add(origin, entry.getLongKey());
                        if (position.equals(controller)) continue;
                        predicate = entry.getValue().beginMatch(snapshot, position, controller);
                    }
                    if (!predicate.advance(work)) return false;
                    var outcome = predicate.result();
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

    public StructureBounds at(BlockPos origin) {
        return new StructureBounds(add(origin, bounds.min()), add(origin, bounds.max()));
    }
}
