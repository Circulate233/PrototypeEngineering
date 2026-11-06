package com.circulation.prototype_eng.api.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.StorageContext;

/** Machine-side FE destination. Capacity holds participate in the same batch as resource holds. */
public interface EnergyOutput {
    StorageContext context();
    EnergySnapshot energySnapshot();
    /** Returns FE accepted under context's lock, within the request, current operation limit and unreserved capacity. */
    long receiveEnergy(long amount, Actionable mode);
    /** Reserves one exact receipt within the current per-operation limit. */
    ReservationResult reserveReceiveEnergy(EnergySnapshot snapshot, long amount);
    long capacity();
    boolean isValid();

    /** External adapters sharing one balance return the same identity for machine budgeting. */
    @SuppressWarnings("unused")
    default Object storageIdentity() { return context(); }
}
