package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;

/**
 * An acquired requirement transaction. The executor commits in acquisition order only when the
 * entire stage succeeds. Blocked/failed stages roll back ALL acquisitions in reverse order,
 * including the transaction that rejected the stage. Commit must succeed; throwing is fatal and
 * does not imply that previously committed transactions can be rolled back. Rollback must undo
 * acquisition effects completely and must never commit another transaction.
 */
public interface RequirementTransaction {
    RequirementTransaction SUCCESS = new RequirementTransaction() {
        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public void commit() {}
        @Override public void rollback() {}
    };

    ProcessResult result();
    void commit();
    void rollback();
}
