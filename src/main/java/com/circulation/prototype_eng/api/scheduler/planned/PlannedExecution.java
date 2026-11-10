package com.circulation.prototype_eng.api.scheduler.planned;

/**
 * One retained owner-controlled execution, including every acquired callback and cleanup obligation.
 * The scheduler owns this exact instance until finished and must retain it after any thrown method.
 * Implementations store partial acquisitions before continuing capture; construction runs no work.
 * A machine execution includes all component continuations and lifecycle leases, not only one recipe
 * round. Methods require the owning thread outside callbacks; none transfer ownership to a worker.
 */
public interface PlannedExecution {
    /**
     * A fresh cleanup budget cannot renew forward authority; a finished execution returns true without replay.
     */
    boolean advance(PlannedDeadline cleanupDeadline);

    /**
     * Proves every accepted asynchronous action has physically exited. Interruption must not make
     * this return early; report it only after exit and restore the interrupt flag. Other failures
     * leave the scheduler without an exit proof and must permit a later confirmation attempt.
     */
    void awaitExit();

    /**
     * Pure fact query: true only after every captured callback, resource and ownership obligation is settled.
     */
    boolean finished();

    /**
     * Idempotently revokes forward work, including after completion, without claiming physical exit.
     */
    void cancel(PlannedCancellationToken.Reason reason);
}
