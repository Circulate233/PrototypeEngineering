package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey2LongMap;

import java.util.Objects;

/**
 * Detached quantities available for planning. The producer must supply a completed map and stop
 * modifying it; consumers borrow the map read-only. Capture from mutable inventory happens at the
 * storage boundary, not again in this record constructor.
 */
public record StorageSnapshot(StorageContext context, long revision, AEKey2LongMap available) {
    public StorageSnapshot {
        Objects.requireNonNull(context);
    }
}
