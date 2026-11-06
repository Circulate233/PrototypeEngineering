package com.circulation.prototype_eng.api.energy;

import ae2.api.config.Actionable;

/** Machine-side FE destination. Access is confined to the owning server thread. */
public interface EnergyOutput {
    /** Returns actual/simulated FE accepted, between zero and the request, within capacity. */
    long receiveEnergy(long amount, Actionable mode);
    long capacity();
    boolean isValid();

    /** External adapters sharing one balance return the same identity for machine budgeting. */
    @SuppressWarnings("unused")
    default Object storageIdentity() { return this; }
}
