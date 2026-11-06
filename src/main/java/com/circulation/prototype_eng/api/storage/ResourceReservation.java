package com.circulation.prototype_eng.api.storage;

/**
 * One exact input quantity or output capacity reservation. All state methods require context's lock.
 * Keep all participant locks from validation through completion or compensation, as ResourceTransactions
 * does; never publish a partly applied batch or persist it as a committed balance.
 * Implementations retain compensation rights after apply until complete. apply must be atomic on
 * failure; complete must not reject a validated, applied reservation. Terminal tokens cannot replay.
 * Scheduler owner/epoch and execution-lane checks remain the enclosing operation's responsibility.
 */
public interface ResourceReservation {
    StorageContext context();
    boolean canApply();
    void apply();
    void complete();
    void rollback();
}
