package com.circulation.prototype_eng.api.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;

/** Independent thread-safe resource balance. AE2 provides resource identity, not storage semantics. */
public interface ResourceStorage {
    StorageContext context();
    StorageSnapshot snapshot();
    long amount(AEKey key);
    long availableAmount(AEKey key);
    long insert(AEKey key, long amount, Actionable mode);
    long extract(AEKey key, long amount, Actionable mode);
    ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount);
    ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount);

    default boolean isValid() {
        return context().isValid();
    }
}
