package com.circulation.prototype_eng.api.storage;

/**
 * One exact input quantity or output capacity reservation. All state methods require context's lock.
 * Keep all participant locks from validation through completion or compensation, as ResourceTransactions
 * does, or keep a ResourceBatch's fence across its source and owner scopes. Never publish a partly
 * applied batch or persist it as a committed balance.
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

    /**
     * Optional exact recovery proof, read under the context lock. UNKNOWN forbids retrying a failed
     * terminal callback: it does not prove that mutation is still running. A reported RESERVED or
     * APPLIED state certifies that the corresponding remaining transition can safely be retried;
     * COMPLETED/ROLLED_BACK certify that it must not be replayed. Implementations unable to provide
     * this proof may still participate, but a throwing terminal callback retains its batch fence.
     */
    default Resolution resolution() { return Resolution.UNKNOWN; }

    enum Resolution { UNKNOWN, RESERVED, APPLIED, COMPLETED, ROLLED_BACK }
}
