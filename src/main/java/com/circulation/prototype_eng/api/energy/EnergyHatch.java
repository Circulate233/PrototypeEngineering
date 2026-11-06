package com.circulation.prototype_eng.api.energy;

import ae2.api.config.Actionable;

/** Independent FE storage; no AE resource key is required. */
public interface EnergyHatch extends EnergyInput {
    long capacity();

    /** Returns actual/simulated FE accepted without exceeding capacity. */
    long receiveEnergy(long amount, Actionable mode);
}
