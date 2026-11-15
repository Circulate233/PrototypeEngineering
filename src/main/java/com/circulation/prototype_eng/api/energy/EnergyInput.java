package com.circulation.prototype_eng.api.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.StorageContext;

/**
 * Machine-side FE source. Views of one balance share its lock, lifetime and reservations.
 */
public interface EnergyInput {
    StorageContext context();

    EnergySnapshot energySnapshot();

    long storedEnergy();

    /**
     * Returns actual/simulated machine consumption from the unreserved balance, without external transfer limits.
     * The result is between zero and the requested amount; the operation shares context's lock.
     */
    long extractEnergy(long amount, Actionable mode);

    /**
     * Reserves exact machine consumption from the unreserved balance, without external transfer limits.
     */
    ReservationResult reserveExtractEnergy(EnergySnapshot snapshot, long amount);

    /**
     * Stable identity of the underlying balance. Adapters of the same balance must
     * return the same object, so machines do not budget that balance twice.
     */
    default Object storageIdentity() {
        return context();
    }

    boolean isValid();
}
