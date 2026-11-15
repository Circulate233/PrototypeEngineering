package com.circulation.prototype_eng.api.transaction;

import com.circulation.prototype_eng.api.recipe.requirement.BudgetResult;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import org.jetbrains.annotations.Nullable;

/**
 * One acquired business effect. Commit retains complete undo information, including on failure;
 * rollback undoes acquisition and commit. Completion relinquishes undo after the whole batch decides
 * success. Terminal callbacks must not throw; an extension that does fail must provide an exact
 * resolution before it can be retried. The owning coordinator supplies the required execution scope.
 */
public interface ReversibleTransaction {
    /**
     * One owner-side admission attempt before any ordinary business commit in the enclosing batch.
     * May apply reversible effects, including committing a budget receipt; false rejects the whole
     * batch normally. Every acquired participant is then rolled back, including this rejecting one
     * and participants not yet prepared. A throwing attempt must also retain its full undo state.
     * Coordinators and composite wrappers must never replay this attempt, even after rejection or
     * failure. The default preserves existing transactions without additional preparation effects.
     */
    default boolean prepareCommit() {
        return true;
    }

    /**
     * One read-only owner check after all business commits and before the batch decides completion.
     * False still chooses ordinary whole-batch compensation. Check the original captured authority
     * and content; do not mutate or reacquire work. Wrappers preserve the original callback scope.
     */
    default boolean validateCommit() {
        return true;
    }

    void commit();

    default void complete() {
    }

    void rollback();

    /**
     * Queries this exact acquisition without applying new business effects. Confirmation may release
     * framework acquisition bookkeeping. REVERSIBLE certifies that the already
     * decided terminal transition can safely be retried, including any partially attempted cleanup;
     * it never authorizes replaying commit. UNKNOWN leaves confirmation pending. Terminal states
     * certify that the transition must not be repeated. Queries must retain the original scope.
     */
    default Resolution resolution() {
        return Resolution.UNKNOWN;
    }

    /**
     * Resumes preparation on this exact transaction and caller scope. YIELDED pauses work,
     * REJECTED is the original known rejection, and ACCEPTED is completed preparation. A yield is neither
     * failure nor UNKNOWN and must resume the same instance without replaying completed effects.
     * The default charges one cooperative legacy callback; owned implementations meter their loops.
     */
    default BudgetResult prepareCommitWithinBudget(RecipeWorkBudget budget) {
        return budget.take() ? BudgetResult.of(prepareCommit()) : BudgetResult.YIELDED;
    }

    /**
     * Resumes read-only validation on the same committed transaction. YIELDED pauses work;
     * REJECTED rejects the original batch normally; ACCEPTED certifies completed validation. No result
     * grants a new authority, acquisition, commit attempt, or callback scope.
     */
    default BudgetResult validateCommitWithinBudget(RecipeWorkBudget budget) {
        return budget.take() ? BudgetResult.of(validateCommit()) : BudgetResult.YIELDED;
    }

    /**
     * True completes this original commit; false yields its same cursor with full undo retained.
     */
    default boolean commitWithinBudget(RecipeWorkBudget budget) {
        if (!budget.take()) return false;
        commit();
        return true;
    }

    /**
     * True confirms original completion; false yields, without certifying any terminal outcome.
     */
    default boolean completeWithinBudget(RecipeWorkBudget budget) {
        if (!budget.take()) return false;
        complete();
        return true;
    }

    /**
     * True confirms original rollback; false yields, without replaying an uncertain callback.
     */
    default boolean rollbackWithinBudget(RecipeWorkBudget budget) {
        if (!budget.take()) return false;
        rollback();
        return true;
    }

    /**
     * Null is a query work yield. UNKNOWN is the original unresolved outcome; REVERSIBLE permits
     * only the already decided terminal retry. Terminal values remain exact receipts from this
     * original transaction. A normal yield never becomes uncertainty merely because work paused.
     */
    default @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
        return budget.take() ? resolution() : null;
    }

    enum Resolution {UNKNOWN, REVERSIBLE, COMPLETED, ROLLED_BACK}
}
