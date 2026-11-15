package com.circulation.prototype_eng.api.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.StorageContext;

/**
 * Machine-side FE destination. Capacity holds participate in the same batch as resource holds.
 */
public interface EnergyOutput {
    StorageContext context();

    EnergySnapshot energySnapshot();

    /**
     * Returns machine-produced FE accepted under context's lock, within the request and unreserved capacity.
     * External transfer limits do not apply.
     */
    long receiveEnergy(long amount, Actionable mode);

    /**
     * Reserves exact machine production within unreserved capacity, without external transfer limits.
     */
    ReservationResult reserveReceiveEnergy(EnergySnapshot snapshot, long amount);

    long capacity();

    boolean isValid();

    /**
     * External adapters sharing one balance return the same identity for machine budgeting.
     */
    @SuppressWarnings("unused")
    default Object storageIdentity() {
        return context();
    }
}
