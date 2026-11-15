package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.BudgetResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A detached match and its actual read proof; neither contains permission to publish a machine.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class StructureEvaluation {
    private final StructurePlan plan;
    private final StructureSnapshot snapshot;
    private final StructureReadPlan used;
    @Nullable
    private final StructureMatch match;
    private final StructurePredicateResult status;

    StructureEvaluation(StructurePlan plan, StructureSnapshot snapshot, StructureReadPlan used, StructureMatchResult result) {
        this.plan = plan;
        this.snapshot = snapshot;
        this.used = used;
        match = result.match();
        status = result.status();
    }

    @Nullable
    public StructureMatch match() {
        return match;
    }

    public StructurePredicateResult status() {
        return status;
    }

    public List<StructureSnapshot.Failure> failures() {
        return snapshot.failures();
    }

    /**
     * Only reads that actually affected this attempt are rechecked; unvisited branches are irrelevant.
     */
    public boolean isCurrent(StructureReadSource source) {
        return snapshot.equivalent(StructureSnapshot.capture(used, source), used);
    }

    /**
     * YIELDED denotes an incomplete proof; prefixes from different ticks never prove current.
     */
    public BudgetResult isCurrent(StructureReadSource source, RecipeWorkBudget work) {
        var captured = StructureSnapshot.capture(used, source, work);
        return captured == null ? BudgetResult.YIELDED : snapshot.equivalent(captured, used, work);
    }

    public StructurePlan plan() {
        return plan;
    }

    /**
     * Reuses the worker result when its proof still holds. Changed facts need a fresh capture/match:
     * time predicates can remain true and greedy slice counts or selected branches can change.
     * This fallback runs on the server boundary and must be included in the scheduler's work budget.
     * Read failures are never a mismatch; a failure still visited by the fresh match propagates.
     */
    public StructureEvaluation refresh(StructureReadSource source) {
        return isCurrent(source) ? this : plan.evaluate(StructureSnapshot.capture(plan.reads(), source));
    }
}
