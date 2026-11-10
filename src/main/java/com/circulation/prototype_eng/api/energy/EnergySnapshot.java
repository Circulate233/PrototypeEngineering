package com.circulation.prototype_eng.api.energy;

import com.circulation.prototype_eng.api.storage.StorageContext;

import java.util.Objects;

/**
 * Immutable FE availability and per-operation limits, without an AE resource key.
 */
public record EnergySnapshot(StorageContext context, long revision, long availableEnergy,
                             long availableCapacity, long maxReceive, long maxExtract) {
    public EnergySnapshot {
        Objects.requireNonNull(context);
        if (revision < 0 || availableEnergy < 0 || availableCapacity < 0 || maxReceive < 0 || maxExtract < 0) {
            throw new IllegalArgumentException("Negative energy snapshot value");
        }
    }
}
