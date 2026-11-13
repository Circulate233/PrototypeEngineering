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
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable fixed pattern, followed by base validators and children anchored at its offset origin.
 */
@SuppressWarnings("unused") // Public extensible definitions use identity, not recursive structural equality.
public class TemplateStructure implements MachineStructure, StructurePreviewProvider {
    private final ResourceLocation id;
    private final BlockOrientation orientation;
    private final BlockPos offset;
    private final boolean hideWorldBlocks;
    private final StructurePattern pattern;
    private final List<StructurePredicate> validators;
    private final List<MachineStructure> children;
    private final boolean budgeted;
    private final boolean planReusable;

    public TemplateStructure(ResourceLocation id, BlockOrientation orientation, BlockPos offset,
                             boolean hideWorldBlocks, StructurePattern pattern,
                             List<StructurePredicate> validators, List<MachineStructure> children) {
        this(id, orientation, offset, hideWorldBlocks, pattern, validators, children, false, false, false);
    }

    private TemplateStructure(ResourceLocation id, BlockOrientation orientation, BlockPos offset, boolean hideWorldBlocks, StructurePattern pattern, List<StructurePredicate> validators, List<MachineStructure> children, boolean budgeted, boolean owned, boolean reusable) {
        this.id = Objects.requireNonNull(id);
        this.orientation = Objects.requireNonNull(orientation);
        this.offset = Objects.requireNonNull(offset).toImmutable();
        this.hideWorldBlocks = hideWorldBlocks;
        this.pattern = Objects.requireNonNull(pattern);
        this.budgeted = budgeted;
        if (owned) {
            this.validators = Collections.unmodifiableList(validators);
            this.children = Collections.unmodifiableList(children);
            planReusable = reusable;
        } else {
            var copiedValidators = new ObjectArrayList<StructurePredicate>(validators.size());
            var copiedChildren = new ObjectArrayList<MachineStructure>(children.size());
            boolean stable = budgeted && pattern.planReusable();
            for (var validator : validators) {
                copiedValidators.add(Objects.requireNonNull(validator));
                stable = stable && validator.planReusable();
            }
            for (var child : children) {
                copiedChildren.add(Objects.requireNonNull(child));
                stable = stable && child.planReusable();
            }
            this.validators = Collections.unmodifiableList(copiedValidators);
            this.children = Collections.unmodifiableList(copiedChildren);
            planReusable = stable;
        }
    }

    /**
     * Explicit default Java factory; ordinary constructor subclasses retain all old virtual hooks.
     */
    public static TemplateStructure create(ResourceLocation id, BlockOrientation orientation, BlockPos offset, boolean hideWorldBlocks, StructurePattern pattern, List<StructurePredicate> validators, List<MachineStructure> children) {
        return new TemplateStructure(id, orientation, offset, hideWorldBlocks, pattern, validators, children, true, false, false);
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
        return new Geometry(Kind.TEMPLATE, offset, 1, 1, BlockPos.ORIGIN,
            positions, children);
    }

    @Override
    public TemplateStructure transform(StructureTransform rotation) {
        if (budgeted) return (TemplateStructure) Objects.requireNonNull(beginTransform(rotation).run());
        Objects.requireNonNull(rotation);
        BlockOrientation transformedOrientation = BlockOrientation.get(
            rotation.apply(orientation.getSide(RelativeSide.FRONT)),
            rotation.apply(orientation.getSide(RelativeSide.TOP)));
        var transformedValidators = new ObjectArrayList<StructurePredicate>(validators.size());
        for (StructurePredicate validator : validators) {
            transformedValidators.add(validator.transform(rotation));
        }
        var transformedChildren = new ObjectArrayList<MachineStructure>(children.size());
        for (MachineStructure child : children) {
            transformedChildren.add(child.transform(rotation));
        }
        return new TemplateStructure(id, transformedOrientation, rotation.apply(offset), hideWorldBlocks,
            pattern.transform(rotation), transformedValidators, transformedChildren);
    }

    @Override
    public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        if (budgeted) {
            beginCollectReads(plan, anchor, controller).run();
            return;
        }
        Objects.requireNonNull(plan);
        Objects.requireNonNull(controller);
        BlockPos origin = StructurePattern.add(anchor, offset);
        pattern.collectReads(plan, origin, controller);
        for (StructurePredicate validator : validators) {
            validator.collectReads(plan, origin, controller);
        }
        for (MachineStructure child : children) {
            child.collectReads(plan, origin, controller);
        }
    }

    @Override
    @Nullable
    public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
        if (budgeted) return Objects.requireNonNull(beginMatch(snapshot, anchor, controller).run()).match();
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(controller);
        BlockPos origin = StructurePattern.add(anchor, offset);
        if (!pattern.matches(snapshot, origin, controller)) {
            return null;
        }
        for (StructurePredicate validator : validators) {
            if (!validator.matches(snapshot, origin, controller)) {
                return null;
            }
        }
        var matchedChildren = new ObjectArrayList<StructureMatch>(children.size());
        for (MachineStructure child : children) {
            StructureMatch result = child.match(snapshot, origin, controller);
            if (result == null) {
                return null;
            }
            matchedChildren.add(result);
        }
        var blocks = new ObjectArrayList<BlockPos>(pattern.blocks().size());
        for (var iterator = pattern.blocks().longKeySet().iterator(); iterator.hasNext(); ) {
            BlockPos position = StructurePattern.add(origin, iterator.nextLong());
            if (!position.equals(controller)) {
                blocks.add(position);
            }
        }
        return new StructureMatch(this, origin, 1, blocks, matchedChildren);
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // This private default cursor retains immutable definition data only.
    public StructureOperation<MachineStructure> beginTransform(StructureTransform rotation) {
        Objects.requireNonNull(rotation);
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
                Objects.requireNonNull(work);
                if (complete()) return true;
                if (!patternWork.advance(work)) return false;
                reusable = reusable && Objects.requireNonNull(patternWork.result()).planReusable();
                while (validatorIndex < validators.size()) {
                    if (validator == null) {
                        if (!work.take()) return false;
                        validator = validators.get(validatorIndex).beginTransform(rotation);
                    }
                    if (!validator.advance(work)) return false;
                    transformedValidators.add(Objects.requireNonNull(validator.result()));
                    reusable = reusable && Objects.requireNonNull(validator.result()).planReusable();
                    validator = null;
                    validatorIndex++;
                }
                while (childIndex < children.size()) {
                    if (child == null) {
                        if (!work.take()) return false;
                        child = children.get(childIndex).beginTransform(rotation);
                    }
                    if (!child.advance(work)) return false;
                    transformedChildren.add(Objects.requireNonNull(child.result()));
                    reusable = reusable && Objects.requireNonNull(child.result()).planReusable();
                    child = null;
                    childIndex++;
                }
                if (!work.take()) return false;
                var actualOrientation = BlockOrientation.get(rotation.apply(orientation.getSide(RelativeSide.FRONT)),
                    rotation.apply(orientation.getSide(RelativeSide.TOP)));
                finish(new TemplateStructure(id, actualOrientation, rotation.apply(offset), hideWorldBlocks,
                    Objects.requireNonNull(patternWork.result()), transformedValidators, transformedChildren, true, true, reusable));
                return true;
            }
        };
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The default cursor retains its immutable declaration and private builder.
    public StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(anchor);
        Objects.requireNonNull(controller);
        if (!budgeted) return MachineStructure.super.beginCollectReads(plan, anchor, controller);
        var origin = StructurePattern.add(anchor, offset);
        return new StructureOperation<>() {
            @Nullable
            private StructureOperation<Void> pending;
            private long patternIndex;
            private int validatorIndex, childIndex;
            private long childCount = 1;
            private boolean patternsComplete, begun;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                if (!begun) {
                    if (!work.take()) return false;
                    begun = true;
                }
                while (!patternsComplete) {
                    if (patternIndex == 1) {
                        patternsComplete = true;
                        break;
                    }
                    if (pending == null) {
                        if (!work.take()) return false;
                        pending = pattern.beginCollectReads(plan, origin, controller);
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
                while (!children.isEmpty() && childCount <= 1) {
                    while (childIndex < children.size()) {
                        if (pending == null) {
                            if (!work.take()) return false;
                            pending = children.get(childIndex).beginCollectReads(plan, origin, controller);
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
    @SuppressWarnings("ReturnOfInnerClass") // Only the explicit private default instance owns this matching cursor.
    public StructureOperation<StructureMatchResult> beginMatch(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(anchor);
        Objects.requireNonNull(controller);
        if (!budgeted) return MachineStructure.super.beginMatch(snapshot, anchor, controller);
        var origin = StructurePattern.add(anchor, offset);
        return new StructureOperation<>() {
            private final StructureOperation<StructurePredicateResult> patternWork = pattern.beginMatch(snapshot, origin, controller);
            private final ObjectArrayList<StructureMatch> matchedChildren = new ObjectArrayList<>();
            private final ObjectArrayList<BlockPos> matchedBlocks = new ObjectArrayList<>();
            private final LongIterator positions = pattern.blocks().longKeySet().iterator();
            @Nullable
            private StructureOperation<StructurePredicateResult> validator;
            @Nullable
            private StructureOperation<StructureMatchResult> child;
            @Nullable
            private StructureOperation<StructureMatch> freeze;
            private int validatorIndex, childIndex;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                if (!patternWork.advance(work)) return false;
                var patternStatus = Objects.requireNonNull(patternWork.result());
                if (patternStatus != StructurePredicateResult.MATCHED) {
                    finish(new StructureMatchResult(patternStatus, null));
                    return true;
                }
                while (validatorIndex < validators.size()) {
                    if (validator == null) {
                        if (!work.take()) return false;
                        validator = validators.get(validatorIndex).beginMatch(snapshot, origin, controller);
                    }
                    if (!validator.advance(work)) return false;
                    var status = Objects.requireNonNull(validator.result());
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
                        child = children.get(childIndex).beginMatch(snapshot, origin, controller);
                    }
                    if (!child.advance(work)) return false;
                    var outcome = Objects.requireNonNull(child.result());
                    if (outcome.status() != StructurePredicateResult.MATCHED) {
                        finish(outcome);
                        return true;
                    }
                    matchedChildren.add(Objects.requireNonNull(outcome.match()));
                    child = null;
                    childIndex++;
                }
                while (positions.hasNext()) {
                    if (!work.take()) return false;
                    var position = StructurePattern.add(origin, positions.nextLong());
                    if (!position.equals(controller)) matchedBlocks.add(position);
                }
                if (freeze == null) {
                    if (!work.take()) return false;
                    freeze = StructureMatch.beginCreate(TemplateStructure.this, origin, 1, matchedBlocks, matchedChildren);
                }
                if (!freeze.advance(work)) return false;
                finish(StructureMatchResult.matched(Objects.requireNonNull(freeze.result())));
                return true;
            }
        };
    }
}
