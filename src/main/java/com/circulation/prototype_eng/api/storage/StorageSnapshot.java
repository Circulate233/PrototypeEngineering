package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey2LongMap;

import java.util.Objects;

/** Immutable quantities available for planning; no mutable inventory or world references. */
public record StorageSnapshot(StorageContext context, long revision, AEKey2LongMap available) {
    public StorageSnapshot {
        Objects.requireNonNull(context);
        var copy = new AEKey2LongMap.LinkdOpenHashMap();
        copy.putAll(Objects.requireNonNull(available));
        available = new AEKey2LongMap.UnmodifiableMap(copy);
    }
}
