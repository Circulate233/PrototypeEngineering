package com.circulation.prototype_eng.api.energy;

/**
 * Extension contract combining machine energy source and destination roles.
 * Implementations provide their own storage without inheriting an internal tile.
 */
public interface EnergyHatch extends EnergyInput, EnergyOutput {
    /**
     * External input limit. Call while holding context's lock.
     */
    default long externalReceiveLimit() {
        return energySnapshot().maxReceive();
    }

    /**
     * External output limit. Call while holding context's lock.
     */
    default long externalExtractLimit() {
        return energySnapshot().maxExtract();
    }

    @Override
    default Object storageIdentity() {
        return EnergyInput.super.storageIdentity();
    }
}
