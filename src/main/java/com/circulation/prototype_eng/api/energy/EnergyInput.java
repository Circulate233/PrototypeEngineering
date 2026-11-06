package com.circulation.prototype_eng.api.energy;

import ae2.api.config.Actionable;

/** Machine-side FE source. All access is confined to the owning server thread. */
public interface EnergyInput {
    long storedEnergy();

    /** Returns actual/simulated FE extracted, between zero and the requested amount. */
    long extractEnergy(long amount, Actionable mode);

    /**
     * Stable identity of the underlying balance. Adapters of the same balance must
     * return the same object, so machines do not budget that balance twice.
     */
    default Object storageIdentity() {
        return this;
    }

    boolean isValid();
}
