package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;


/**
 * One transformed definition, controller anchor and complete declaration of potential reads.
 */
public final class StructurePlan {
    private final MachineStructure structure;
    private final BlockPos controller;
    private final StructureReadPlan reads;

    public StructurePlan(MachineStructure definition, BlockPos controller, BlockOrientation orientation) {
        var prepared = beginCreate(definition, controller, orientation).run();
        structure = prepared.structure;
        this.controller = prepared.controller;
        reads = prepared.reads;
    }

    private StructurePlan(MachineStructure structure, BlockPos controller, StructureReadPlan reads) {
        this.structure = structure;
        this.controller = controller;
        this.reads = reads;
    }

    /**
     * Transform, maximal declaration and freeze each resume through the actual selected hooks.
     */
    public static StructureOperation<StructurePlan> beginCreate(MachineStructure definition, BlockPos controller, BlockOrientation orientation) {
        var actual = controller.toImmutable();
        var rotation = StructureTransform.between(definition.orientation(), orientation);
        return beginCreate(actual, definition.beginTransform(rotation));
    }

    /**
     * Configured hosts may share completed canonical transforms; standalone calls keep their virtual path.
     */
    public static StructureOperation<StructurePlan> beginCreate(StructureConfiguration configuration, BlockPos controller) {
        var definition = configuration.definition();
        var orientation = configuration.orientation();
        var catalog = configuration.catalog();
        if (catalog == null || !definition.planReusable()) return beginCreate(definition, controller, orientation);
        var rotation = StructureTransform.between(definition.orientation(), orientation);
        var transform = new StructureOperation<MachineStructure>() {
            @Nullable
            private StructureOperation<MachineStructure> pending;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                if (pending == null) {
                    if (!work.take()) return false;
                    var cached = catalog.cachedTransform(definition, orientation);
                    if (cached != null) {
                        finish(cached);
                        return true;
                    }
                    pending = definition.beginTransform(rotation);
                }
                if (!pending.advance(work) || !work.take()) return false;
                var result = pending.result();
                finish(result.planReusable() ? catalog.rememberTransform(definition, orientation, result) : result);
                return true;
            }
        };
        return beginCreate(controller, transform);
    }

    private static StructureOperation<StructurePlan> beginCreate(BlockPos controller, StructureOperation<MachineStructure> transform) {
        var actual = controller.toImmutable();
        return new StructureOperation<>() {
            private final StructureReadPlan.Builder builder = new StructureReadPlan.Builder();
            @Nullable
            private StructureOperation<@Nullable Void> declaration;
            @Nullable
            private StructureOperation<StructureReadPlan> freeze;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                if (!transform.advance(work)) return false;
                if (declaration == null) {
                    if (!work.take()) return false;
                    declaration = transform.result().beginCollectReads(builder, actual, actual);
                }
                if (!declaration.advance(work)) return false;
                if (freeze == null) {
                    if (!work.take()) return false;
                    freeze = builder.beginBuild();
                }
                if (!freeze.advance(work)) return false;
                if (!work.take()) return false;
                finish(new StructurePlan(transform.result(), actual, freeze.result()));
                return true;
            }
        };
    }

    public StructureReadPlan reads() {
        return reads;
    }

    /**
     * Whether the actual transformed definition is safe to retain across independent attempts.
     */
    public boolean planReusable() {
        return structure.planReusable();
    }

    /**
     * Independent attempts cannot retain a successful tree after a later failed match.
     */
    @Nullable
    public StructureMatch match(StructureSnapshot snapshot) {
        return structure.match(snapshot, controller, controller);
    }

    /**
     * Worker-safe evaluation; each invocation records its own actual reads, including failed probes.
     */
    public StructureEvaluation evaluate(StructureSnapshot snapshot) {
        return beginEvaluate(snapshot).run();
    }

    @SuppressWarnings("ReturnOfInnerClass")
    // The cursor retains only this immutable plan and its detached private trace.
    public StructureOperation<StructureEvaluation> beginEvaluate(StructureSnapshot snapshot) {
        return new StructureOperation<>() {
            private final StructureReadPlan.Builder used = new StructureReadPlan.Builder();
            private final StructureOperation<StructureMatchResult> match = structure.beginMatch(snapshot.tracking(used), controller, controller);
            @Nullable
            private StructureOperation<StructureReadPlan> freeze;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                if (!match.advance(work)) return false;
                if (freeze == null) {
                    if (!work.take()) return false;
                    freeze = used.beginBuild();
                }
                if (!freeze.advance(work)) return false;
                if (!work.take()) return false;
                finish(new StructureEvaluation(StructurePlan.this, snapshot, freeze.result(), match.result()));
                return true;
            }
        };
    }
}
