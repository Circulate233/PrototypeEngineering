package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;

import java.util.Objects;

/** Immutable quantities available for planning; no mutable inventory or world references. */
public record StorageSnapshot(StorageContext context, long revision, Object2LongMap<AEKey> available) {
    public StorageSnapshot {
        Objects.requireNonNull(context);
        available = Object2LongMaps.unmodifiable(new Object2LongLinkedOpenHashMap<>(available));
    }
}
