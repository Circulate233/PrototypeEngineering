package com.circulation.prototype_eng.api.energy;

/**
 * Extension contract combining machine energy source and destination roles.
 * Implementations provide their own storage without inheriting an internal tile.
 */
public interface EnergyHatch extends EnergyInput, EnergyOutput {
    @Override
    default Object storageIdentity() {
        return EnergyInput.super.storageIdentity();
    }
}
