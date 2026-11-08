package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.AbstractList;

/** A stage owns these tokens. Individual requirements must never settle them independently. */
record ReservedRequirement(ProcessResult result, List<ResourceReservation> reservations) implements RequirementTransaction {
    ReservedRequirement {
        reservations = reservations instanceof FrozenReservations ? reservations : ObjectLists.unmodifiable(new ObjectArrayList<>(reservations));
    }

    static ReservedRequirement frozen(ProcessResult result, List<ResourceReservation> reservations) {
        return new ReservedRequirement(result, new FrozenReservations(reservations));
    }

    /** Library-private proof: this original invocation never appends after transferring inventory. */
    private static final class FrozenReservations extends AbstractList<ResourceReservation> {
        private final List<ResourceReservation> values;
        private FrozenReservations(List<ResourceReservation> values) { this.values = values; }
        @Override public ResourceReservation get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
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
