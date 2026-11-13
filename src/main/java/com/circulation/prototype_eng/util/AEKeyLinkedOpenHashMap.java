package com.circulation.prototype_eng.util;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;

import java.io.Serial;

/**
 * AE quantities in insertion order with an explicit expected entry count.
 */
@SuppressWarnings("CloneableClassInSecureContext") // Mutable fastutil clones own independent key/value arrays.
public final class AEKeyLinkedOpenHashMap extends Object2LongLinkedOpenHashMap<AEKey> implements AEKey2LongMap {
    @Serial
    private static final long serialVersionUID = 1L;

    public AEKeyLinkedOpenHashMap(int expected) {
        super(expected);
    }

    @Override
    public long addTo(AEKey key, long increment) {
        return super.addTo(key, increment);
    }
}
