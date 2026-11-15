package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructureMatchResult;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicateResult;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Greedy consecutive slices. Validators run once at the base; children run once at the last matched
 * slice, with zero matches placing children one sliceOffset before the base. Child failure does not
 * retry a shorter length. An unavailable attempted slice fails the whole structure.
 * Validator and child lists are borrowed read-only for the definition's lifetime.
 */
@SuppressWarnings("unused") // Public extensible definitions use identity, not recursive structural equality.
public class SliceStructure implements MachineStructure, StructurePreviewProvider {
    private final ResourceLocation id;
    private final BlockOrientation orientation;
    private final BlockPos offset;
    private final boolean hideWorldBlocks;
    private final StructurePattern pattern;
    private final int minCount;
    private final int maxCount;
    private final BlockPos sliceOffset;
    private final List<StructurePredicate> validators;
    private final List<MachineStructure> children;
    private final boolean budgeted;
    private final boolean planReusable;

    public SliceStructure(ResourceLocation id, BlockOrientation orientation, BlockPos offset,
                          boolean hideWorldBlocks, StructurePattern pattern, int minCount, int maxCount,
                          BlockPos sliceOffset, List<StructurePredicate> validators, List<MachineStructure> children) {
        this(id, orientation, offset, hideWorldBlocks, pattern, minCount, maxCount, sliceOffset, validators, children, false, false, false);
    }

    private SliceStructure(ResourceLocation id, BlockOrientation orientation, BlockPos offset, boolean hideWorldBlocks, StructurePattern pattern, int minCount, int maxCount, BlockPos sliceOffset, List<StructurePredicate> validators, List<MachineStructure> children, boolean budgeted, boolean owned, boolean reusable) {
        if (minCount < 0 || maxCount < minCount) {
            throw new IllegalArgumentException("Slice counts must satisfy 0 <= minCount <= maxCount");
        }
        this.id = id;
        this.orientation = orientation;
        this.offset = offset.toImmutable();
        this.hideWorldBlocks = hideWorldBlocks;
        this.pattern = pattern;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.sliceOffset = sliceOffset.toImmutable();
        this.budgeted = budgeted;
        this.validators = validators;
        this.children = children;
        if (owned) {
            planReusable = reusable;
        } else {
            boolean stable = budgeted && pattern.planReusable();
            for (int index = 0; stable && index < validators.size(); index++)
                stable = validators.get(index).planReusable();
            for (int index = 0; stable && index < children.size(); index++)
                stable = children.get(index).planReusable();
            planReusable = stable;
        }
    }

    /**
     * Explicit default Java factory; ordinary constructor subclasses retain all old virtual hooks.
     */
    public static SliceStructure create(ResourceLocation id, BlockOrientation orientation, BlockPos offset, boolean hideWorldBlocks, StructurePattern pattern, int minCount, int maxCount, BlockPos sliceOffset, List<StructurePredicate> validators, List<MachineStructure> children) {
        return new SliceStructure(id, orientation, offset, hideWorldBlocks, pattern, minCount, maxCount, sliceOffset, validators, children, true, false, false);
    }

    @Override
    public boolean planReusable() {
        return planReusable;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public BlockOrientation orientation() {
        return orientation;
    }

    public BlockPos offset() {
        return offset;
    }

    @Override
    public boolean hideWorldBlocks() {
        return hideWorldBlocks;
    }

    @Override
    public BlockPos2ObjectMap<StructureDisplay> displays() {
        return pattern.displays();
    }

    public StructurePattern pattern() {
        return pattern;
    }

    public int minCount() {
        return minCount;
    }

    public int maxCount() {
        return maxCount;
    }

    public BlockPos sliceOffset() {
        return sliceOffset;
    }

    public List<StructurePredicate> validators() {
        return validators;
    }

    public List<MachineStructure> children() {
        return children;
    }

    @Override
    public Geometry previewGeometry() {
        var positions = new ObjectArrayList<BlockPos>(pattern.blocks().size());
        for (var iterator = pattern.blocks().longKeySet().iterator(); iterator.hasNext(); )
            positions.add(BlockPos.fromLong(iterator.nextLong()));
        return new Geometry(Kind.SLICE, offset, minCount, maxCount, sliceOffset,
            positions, children);
    }

    @Override
    public SliceStructure transform(StructureTransform rotation) {
        BlockOrientation transformedOrientation = BlockOrientation.get(
            rotation.apply(orientation.getSide(RelativeSide.FRONT)),
            rotation.apply(orientation.getSide(RelativeSide.TOP)));
        var transformedValidators = new ObjectArrayList<StructurePredicate>(validators.size());
        for (int index3 = 0; index3 < validators.size(); index3++) {
            StructurePredicate validator = validators.get(index3);
            transformedValidators.add(validator.transform(rotation));
        }
        var transformedChildren = new ObjectArrayList<MachineStructure>(children.size());
        for (int index1 = 0; index1 < children.size(); index1++) {
            MachineStructure child = children.get(index1);
            transformedChildren.add(child.transform(rotation));
        }
        return new SliceStructure(id, transformedOrientation, rotation.apply(offset), hideWorldBlocks,
            pattern.transform(rotation), minCount, maxCount, rotation.apply(sliceOffset),
            transformedValidators, transformedChildren);
    }

    @Override
    public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        if (budgeted) {
            beginCollectReads(plan, anchor, controller).run();
            return;
        }
        BlockPos origin = StructurePattern.add(anchor, offset);
        for (long index = 0; index < maxCount; index++) {
            pattern.collectReads(plan, sliceOrigin(origin, Math.toIntExact(index)), controller);
        }
        for (int index2 = 0; index2 < validators.size(); index2++) {
            StructurePredicate validator = validators.get(index2);
            validator.collectReads(plan, origin, controller);
        }
        if (!children.isEmpty()) {
            for (long count = minCount; count <= maxCount; count++) {
                BlockPos childOrigin = sliceOrigin(origin, Math.toIntExact(count - 1));
                for (int index4 = 0; index4 < children.size(); index4++) {
                    MachineStructure child = children.get(index4);
                    child.collectReads(plan, childOrigin, controller);
                }
            }
        }
    }

    @Override
    @Nullable
    public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
        if (budgeted) return beginMatch(snapshot, anchor, controller).run().match();
        BlockPos origin = StructurePattern.add(anchor, offset);
        var blocks = new ObjectLinkedOpenHashSet<BlockPos>();
        int count = 0;
        while (count < maxCount) {
            BlockPos current = sliceOrigin(origin, count);
            // Unloaded is a fatal attempted branch, never the greedy pattern-mismatch stop condition.
            if (!snapshot.isAreaLoaded(pattern.at(current))) {
                return null;
            }
            if (!pattern.matches(snapshot, current, controller)) {
                break;
            }
            for (var iterator = pattern.blocks().longKeySet().iterator(); iterator.hasNext(); ) {
                BlockPos position = StructurePattern.add(current, iterator.nextLong());
                if (!position.equals(controller)) {
                    blocks.add(position);
                }
            }
            count++;
        }
        if (count < minCount) {
            return null;
        }
        for (int index6 = 0; index6 < validators.size(); index6++) {
            StructurePredicate validator = validators.get(index6);
            if (!validator.matches(snapshot, origin, controller)) {
                return null;
            }
        }
        var matchedChildren = new ObjectArrayList<StructureMatch>(children.size());
        if (!children.isEmpty()) {
            BlockPos childOrigin = sliceOrigin(origin, Math.toIntExact((long) count - 1));
            for (int index8 = 0; index8 < children.size(); index8++) {
                MachineStructure child = children.get(index8);
                StructureMatch result = child.match(snapshot, childOrigin, controller);
                if (result == null) {
                    return null;
                }
                matchedChildren.add(result);
            }
        }
        return new StructureMatch(this, origin, count, new ObjectArrayList<>(blocks), matchedChildren);
    }

    private BlockPos sliceOrigin(BlockPos origin, int index) {
        return new BlockPos(Math.addExact(origin.getX(), Math.toIntExact((long) sliceOffset.getX() * index)),
            Math.addExact(origin.getY(), Math.toIntExact((long) sliceOffset.getY() * index)),
            Math.addExact(origin.getZ(), Math.toIntExact((long) sliceOffset.getZ() * index)));
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // This private default cursor retains immutable definition data only.
    public StructureOperation<MachineStructure> beginTransform(StructureTransform rotation) {
        if (!budgeted) return MachineStructure.super.beginTransform(rotation);
        return new StructureOperation<>() {
            private final StructureOperation<StructurePattern> patternWork = pattern.beginTransform(rotation);
            private final ObjectArrayList<StructurePredicate> transformedValidators = new ObjectArrayList<>();
            private final ObjectArrayList<MachineStructure> transformedChildren = new ObjectArrayList<>();
            @Nullable
            private StructureOperation<StructurePredicate> validator;
            @Nullable
            private StructureOperation<MachineStructure> child;
            private int validatorIndex, childIndex;
            private boolean reusable = planReusable;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                if (!patternWork.advance(work)) return false;
                reusable = reusable && patternWork.result().planReusable();
                while (validatorIndex < validators.size()) {
                    if (validator == null) {
                        if (!work.take()) return false;
                        validator = validators.get(validatorIndex).beginTransform(rotation);
                    }
                    if (!validator.advance(work)) return false;
                    transformedValidators.add(validator.result());
                    reusable = reusable && validator.result().planReusable();
                    validator = null;
                    validatorIndex++;
                }
                while (childIndex < children.size()) {
                    if (child == null) {
                        if (!work.take()) return false;
                        child = children.get(childIndex).beginTransform(rotation);
                    }
                    if (!child.advance(work)) return false;
                    transformedChildren.add(child.result());
                    reusable = reusable && child.result().planReusable();
                    child = null;
                    childIndex++;
                }
                if (!work.take()) return false;
                var actualOrientation = BlockOrientation.get(rotation.apply(orientation.getSide(RelativeSide.FRONT)),
                    rotation.apply(orientation.getSide(RelativeSide.TOP)));
                finish(new SliceStructure(id, actualOrientation, rotation.apply(offset), hideWorldBlocks,
                    patternWork.result(), minCount, maxCount, rotation.apply(sliceOffset), transformedValidators, transformedChildren, true, true, reusable));
                return true;
            }
        };
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The default cursor retains its immutable declaration and private builder.
    public StructureOperation<@Nullable Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        if (!budgeted) return MachineStructure.super.beginCollectReads(plan, anchor, controller);
        var origin = StructurePattern.add(anchor, offset);
        return new StructureOperation<>() {
            @Nullable
            private StructureOperation<@Nullable Void> pending;
            private long patternIndex;
            private int validatorIndex, childIndex;
            private long childCount = minCount;
            private boolean patternsComplete, begun;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                if (!begun) {
                    if (!work.take()) return false;
                    begun = true;
                }
                while (!patternsComplete) {
                    if (patternIndex == maxCount) {
                        patternsComplete = true;
                        break;
                    }
                    if (pending == null) {
                        if (!work.take()) return false;
                        pending = pattern.beginCollectReads(plan, sliceOrigin(origin, Math.toIntExact(patternIndex)), controller);
                    }
                    if (!pending.advance(work)) return false;
                    pending = null;
                    patternIndex++;
                }
                while (validatorIndex < validators.size()) {
                    if (pending == null) {
                        if (!work.take()) return false;
                        pending = validators.get(validatorIndex).beginCollectReads(plan, origin, controller);
                    }
                    if (!pending.advance(work)) return false;
                    pending = null;
                    validatorIndex++;
                }
                while (!children.isEmpty() && childCount <= maxCount) {
                    while (childIndex < children.size()) {
                        if (pending == null) {
                            if (!work.take()) return false;
                            pending = children.get(childIndex).beginCollectReads(plan, sliceOrigin(origin, Math.toIntExact(childCount - 1)), controller);
                        }
                        if (!pending.advance(work)) return false;
                        pending = null;
                        childIndex++;
                    }
                    childIndex = 0;
                    childCount++;
                }
                finish(null);
                return true;
            }
        };
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass")
    // Only the explicit private default instance owns this greedy matching cursor.
    public StructureOperation<StructureMatchResult> beginMatch(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
        if (!budgeted) return MachineStructure.super.beginMatch(snapshot, anchor, controller);
        var origin = StructurePattern.add(anchor, offset);
        return new StructureOperation<>() {
            private final ObjectLinkedOpenHashSet<BlockPos> seenBlocks = new ObjectLinkedOpenHashSet<>();
            private final ObjectArrayList<BlockPos> matchedBlocks = new ObjectArrayList<>();
            private final ObjectArrayList<StructureMatch> matchedChildren = new ObjectArrayList<>();
            @Nullable
            private StructureOperation<StructurePredicateResult> patternWork;
            @Nullable
            private StructureOperation<StructurePredicateResult> validator;
            @Nullable
            private StructureOperation<StructureMatchResult> child;
            @Nullable
            private StructureOperation<StructureMatch> freeze;
            @Nullable
            private LongIterator positions;
            @Nullable
            private BlockPos currentOrigin;
            private int count, validatorIndex, childIndex;
            private boolean patternsComplete;

            @Override
            @SuppressWarnings("DataFlowIssue") // The active pattern cursor is created with the current slice origin.
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                while (!patternsComplete) {
                    if (count == maxCount) {
                        patternsComplete = true;
                        break;
                    }
                    if (patternWork == null) {
                        if (!work.take()) return false;
                        currentOrigin = sliceOrigin(origin, count);
                        if (!snapshot.isAreaLoaded(pattern.at(currentOrigin))) {
                            finish(StructureMatchResult.unavailable());
                            return true;
                        }
                        patternWork = pattern.beginMatch(snapshot, currentOrigin, controller);
                    }
                    if (!patternWork.advance(work)) return false;
                    var status = patternWork.result();
                    if (status == StructurePredicateResult.UNAVAILABLE) {
                        finish(StructureMatchResult.unavailable());
                        return true;
                    }
                    if (status == StructurePredicateResult.MISMATCH) {
                        patternsComplete = true;
                        break;
                    }
                    if (positions == null) positions = pattern.blocks().longKeySet().iterator();
                    while (positions.hasNext()) {
                        if (!work.take()) return false;
                        var position = StructurePattern.add(currentOrigin, positions.nextLong());
                        if (!position.equals(controller) && seenBlocks.add(position)) matchedBlocks.add(position);
                    }
                    patternWork = null;
                    positions = null;
                    count++;
                }
                if (count < minCount) {
                    finish(StructureMatchResult.mismatch());
                    return true;
                }
                while (validatorIndex < validators.size()) {
                    if (validator == null) {
                        if (!work.take()) return false;
                        validator = validators.get(validatorIndex).beginMatch(snapshot, origin, controller);
                    }
                    if (!validator.advance(work)) return false;
                    var status = validator.result();
                    if (status != StructurePredicateResult.MATCHED) {
                        finish(new StructureMatchResult(status, null));
                        return true;
                    }
                    validator = null;
                    validatorIndex++;
                }
                while (childIndex < children.size()) {
                    if (child == null) {
                        if (!work.take()) return false;
                        var childOrigin = sliceOrigin(origin, Math.toIntExact((long) count - 1));
                        child = children.get(childIndex).beginMatch(snapshot, childOrigin, controller);
                    }
                    if (!child.advance(work)) return false;
                    var outcome = child.result();
                    if (outcome.status() != StructurePredicateResult.MATCHED) {
                        finish(outcome);
                        return true;
                    }
                    matchedChildren.add(outcome.match());
                    child = null;
                    childIndex++;
                }
                if (freeze == null) {
                    if (!work.take()) return false;
                    freeze = StructureMatch.beginCreate(SliceStructure.this, origin, count, matchedBlocks, matchedChildren);
                }
                if (!freeze.advance(work)) return false;
                finish(StructureMatchResult.matched(freeze.result()));
                return true;
            }
        };
    }
}
