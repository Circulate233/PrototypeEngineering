package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;

/** A stage owns these tokens. Individual requirements must never settle them independently. */
record ReservedRequirement(ProcessResult result, List<ResourceReservation> reservations) implements RequirementTransaction {
    ReservedRequirement {
        reservations = ObjectLists.unmodifiable(new ObjectArrayList<>(reservations));
    }

    @Override
    public void commit() { }

    @Override
    public void rollback() { }

    static void rollbackAfterFailure(List<ResourceReservation> reservations, Throwable failure) {
        try {
            ResourceTransactions.rollback(reservations);
        } catch (RuntimeException | Error cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }
}
