package com.circulation.prototype_eng.api.transaction;

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
    default boolean prepareCommit() { return true; }

    /**
     * One read-only owner check after all business commits and before the batch decides completion.
     * False still chooses ordinary whole-batch compensation. Check the original captured authority
     * and content; do not mutate or reacquire work. Wrappers preserve the original callback scope.
     */
    default boolean validateCommit() { return true; }

    void commit();
    default void complete() { }
    void rollback();

    /**
     * Queries this exact acquisition without applying new business effects. Confirmation may release
     * framework acquisition bookkeeping. REVERSIBLE certifies that the already
     * decided terminal transition can safely be retried, including any partially attempted cleanup;
     * it never authorizes replaying commit. UNKNOWN leaves confirmation pending. Terminal states
     * certify that the transition must not be repeated. Queries must retain the original scope.
     */
    default Resolution resolution() { return Resolution.UNKNOWN; }

    enum Resolution { UNKNOWN, REVERSIBLE, COMPLETED, ROLLED_BACK }
}
