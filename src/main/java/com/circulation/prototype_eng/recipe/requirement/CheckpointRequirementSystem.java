package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementContinuation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PM's complete child lifecycle within one outer-stage settlement boundary. The owner yields each
 * child only after the preceding effects are staged, and never relinquishes outer compensation.
 */
public final class CheckpointRequirementSystem implements RecipeRequirementSystem<CheckpointRequirement> {
    private static final RecipeStage[] CHILD_STAGES = RecipeStage.values();

    @Override
    public List<RecipeRequirement<?>> children(CheckpointRequirement payload) { return List.of(payload.requirement()); }

    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<CheckpointRequirement> requirement) {
        var checkpoint = requirement.payload();
        if (checkpoint.time() != 0) {
            return RequirementTransaction.SUCCESS;
        }
        var state = process.state();
        var tick = new RecipeProcess.Tick(state.progress(), state.progress(), state.successfulTicks(), state.successfulTicks());
        return acquireLifecycle(process, checkpoint.requirement(), tick);
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<CheckpointRequirement> requirement) {
        var checkpoint = requirement.payload();
        if (checkpoint.time() == 0) {
            return RequirementTransaction.SUCCESS;
        }
        var tick = process.tickContext();
        boolean crossed = checkpoint.scaleWithProcess()
            ? tick.progressBefore() < checkpoint.time() && tick.progressAfter() >= checkpoint.time()
            : tick.indexBefore() < checkpoint.time() && tick.indexAfter() >= checkpoint.time();
        return crossed ? acquireLifecycle(process, checkpoint.requirement(), tick) : RequirementTransaction.SUCCESS;
    }

    private static RequirementTransaction acquireLifecycle(RecipeProcess process, RecipeRequirement<?> requirement,
                                                           RecipeProcess.Tick tick) {
        return new Lifecycle(process, requirement, tick);
    }

    /** Only the enclosing executor stages and settles the yielded child transactions. */
    private static final class Lifecycle implements RequirementTransaction, RequirementContinuation {
        private final RecipeProcess process;
        private final RecipeRequirement<?> child;
        private final RecipeProcess.Tick tick;
        private int cursor;
        private Resolution terminal = Resolution.REVERSIBLE;

        private Lifecycle(RecipeProcess process, RecipeRequirement<?> child, RecipeProcess.Tick tick) {
            this.process = process;
            this.child = child;
            this.tick = tick;
        }

        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public RequirementContinuation continuation() { return this; }
        @Override public void commit() { }
        @Override public void complete() { terminal = Resolution.COMPLETED; }
        @Override public void rollback() { terminal = Resolution.ROLLED_BACK; }
        @Override public Resolution resolution() { return terminal; }

        @Override public @Nullable RequirementTransaction acquireNext() {
            if (terminal != Resolution.REVERSIBLE) { throw new IllegalStateException("Checkpoint is already terminal"); }
            if (cursor == CHILD_STAGES.length) { return null; }
            var stage = CHILD_STAGES[cursor++];
            return stage == RecipeStage.TICK
                ? process.withTickContext(tick, () -> child.acquire(process, stage))
                : child.acquire(process, stage);
        }
    }
}
