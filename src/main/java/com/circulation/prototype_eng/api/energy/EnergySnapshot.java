package com.circulation.prototype_eng.api.energy;

import com.circulation.prototype_eng.api.storage.StorageContext;


/**
 * FE availability and external per-operation transfer limits. Machine consumption and production use
 * availableEnergy and availableCapacity without maxExtract or maxReceive.
 */
public record EnergySnapshot(StorageContext context, long revision, long availableEnergy,
                             long availableCapacity, long maxReceive, long maxExtract) {
    public EnergySnapshot {
        if (revision < 0 || availableEnergy < 0 || availableCapacity < 0 || maxReceive < 0 || maxExtract < 0) {
            throw new IllegalArgumentException("Negative energy snapshot value");
        }
    }
}
