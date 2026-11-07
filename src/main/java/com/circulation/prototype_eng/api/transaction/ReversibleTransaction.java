package com.circulation.prototype_eng.api.transaction;

/**
 * One acquired business effect. Commit retains complete undo information, including on failure;
 * rollback undoes acquisition and commit. Completion relinquishes undo after the whole batch decides
 * success. Terminal callbacks must not throw; an extension that does fail must provide an exact
 * resolution before it can be retried. The owning coordinator supplies the required execution scope.
 */
public interface ReversibleTransaction {
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
