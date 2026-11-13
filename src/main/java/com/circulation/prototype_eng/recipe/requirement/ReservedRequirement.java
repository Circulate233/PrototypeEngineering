package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;

import java.util.List;

/**
 * A stage owns these tokens. The supplied list is transferred unchanged through settlement;
 * callers must neither modify it nor settle individual tokens independently.
 */
record ReservedRequirement(ProcessResult result,
                           List<ResourceReservation> reservations) implements RequirementTransaction {
    static void rollbackAfterFailure(List<ResourceReservation> reservations, Throwable failure) {
        try {
            ResourceTransactions.rollback(reservations);
        } catch (RuntimeException | Error cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    @Override
    public void commit() {
    }

    @Override
    public void rollback() {
    }

}
