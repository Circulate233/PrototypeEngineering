package com.circulation.prototype_eng.api.storage;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import org.jetbrains.annotations.Nullable;

/**
 * One exact input quantity or output capacity reservation. All state methods require context's lock.
 * Keep all participant locks from validation through completion or compensation, as ResourceTransactions
 * does, or keep a ResourceBatch's fence across its source and owner scopes. Never publish a partly
 * applied batch or persist it as a committed balance.
 * Implementations retain compensation rights after apply until complete. Fresh synchronous apply
 * must be atomic on failure; budget methods may retain an exact partial journal reporting REVERSIBLE.
 * A false budget result is work yield; primitive uncertainty must throw and report UNKNOWN.
 * Complete must not reject a validated, applied reservation. Terminal tokens cannot replay.
 * Scheduler owner/epoch and execution-lane checks remain the enclosing operation's responsibility.
 */
public interface ResourceReservation {
    StorageContext context();
    boolean canApply();
    void apply();
    void complete();
    void rollback();

    /** Whether applied effects can be reused by later steps in the same isolated sequence. */
    default boolean supportsSequentialUse() { return false; }

    /**
     * Release this applied token's quantity/refund holds to the same retained StorageFence without
     * completing it. Implementations must first call context.retainSequentialReservation() and release
     * that exact retention only after confirmed completion/rollback. The token keeps full undo
     * information, remains APPLIED, and stays live until the enclosing sequence completes or rolls back in reverse acquisition order. No earlier step
     * may be compensated before its dependent later steps. All subsequent terminal actions require
     * the original fence; ordinary readers/writers must remain excluded until settlement is proven.
     * Call only once, after apply, under the context's fence scope. The enclosing coordinator must
     * check supportsSequentialUse before accepting the token into a sequence. External storage
     * implementations opt in only when they can honor this stronger compensation contract.
     */
    default void exposeApplied() { throw new UnsupportedOperationException("Reservation does not support sequential use"); }

    /**
     * Optional exact recovery proof, read under the context lock. UNKNOWN forbids retrying a failed
     * terminal callback: it does not prove that mutation is still running. A reported RESERVED or
     * APPLIED state certifies that the corresponding remaining transition can safely be retried;
     * COMPLETED/ROLLED_BACK certify that it must not be replayed. Implementations unable to provide
     * this proof may still participate, but a throwing terminal callback retains its batch fence.
     */
    default Resolution resolution() { return Resolution.UNKNOWN; }

    /** Null is ordinary work yield; false is a completed rejection. */
    default @Nullable Boolean canApplyWithinBudget(RecipeWorkBudget budget) {
        return budget.take() ? canApply() : null;
    }

    /** False is a resumable work yield, never completion or an unknown terminal effect. */
    default boolean applyWithinBudget(RecipeWorkBudget budget) {
        if (!budget.take()) { return false; }
        apply(); return true;
    }

    default boolean exposeAppliedWithinBudget(RecipeWorkBudget budget) {
        if (!budget.take()) { return false; }
        exposeApplied(); return true;
    }

    default boolean completeWithinBudget(RecipeWorkBudget budget) {
        if (!budget.take()) { return false; }
        complete(); return true;
    }

    default boolean rollbackWithinBudget(RecipeWorkBudget budget) {
        if (!budget.take()) { return false; }
        rollback(); return true;
    }

    /** Null yields. REVERSIBLE certifies an exact partial journal, not a full APPLIED token. */
    default @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
        return budget.take() ? resolution() : null;
    }

    enum Resolution { UNKNOWN, RESERVED, APPLIED, COMPLETED, ROLLED_BACK, REVERSIBLE}
}
