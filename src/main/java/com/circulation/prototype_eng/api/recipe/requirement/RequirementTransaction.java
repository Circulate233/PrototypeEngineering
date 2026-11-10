package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An acquired requirement transaction. The executor stages business commits in acquisition order,
 * retaining every undo until the entire stage succeeds. Blocked/failed stages roll back ALL acquisitions in reverse order,
 * including the transaction that rejected the stage. Commit applies business changes that remain
 * reversible until the entire stage completes. A throwing commit must be atomic or retain complete
 * undo information. Rollback undoes acquisition AND commit effects and must never commit another
 * transaction. The stage coordinator alone applies, completes and rolls back supplied reservations;
 * business callbacks must not resolve those tokens themselves.
 */
public interface RequirementTransaction extends ReversibleTransaction {
    RequirementTransaction SUCCESS = new RequirementTransaction() {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    };

    ProcessResult result();

    /**
     * Stable, side-effect-free list of all acquired tokens, including a partially rejected acquisition.
     */
    default List<? extends ResourceReservation> reservations() {
        return List.of();
    }

    /**
     * Optional ordered tail, acquired only after this transaction's reversible commit. The returned
     * cursor is stable and side-effect-free to obtain. The enclosing executor owns every tail step;
     * this transaction must not also commit, complete or roll back those returned transactions.
     */
    default @Nullable RequirementContinuation continuation() {
        return null;
    }

    @Override
    void commit();

    /**
     * Release business undo information after all commits succeed. Must not reject or throw.
     */
    @Override
    default void complete() {
    }

    @Override
    void rollback();
}
