package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;

/**
 * Builds detached resource quantities. Completed maps are immutable by contract: producers stop
 * writing after handing them off, and consumers must not modify their contents or entry views.
 */
public final class ImmutableResourceAmounts {
    private static final AEKey2LongMap EMPTY = new AEKey2LongMap.OpenHashMap(0);

    private ImmutableResourceAmounts() {
    }

    public static AEKey2LongMap empty() {
        return EMPTY;
    }

    public static AEKey2LongMap single(AEKey key, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative resource snapshot amount");
        if (amount == 0) return EMPTY;
        var result = new AEKey2LongMap.OpenHashMap(1);
        result.put(key, amount);
        return result;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The owner may add quantities until build(), then transfers the map without copying.
     */
    public static final class Builder {
        private final AEKey2LongMap values = new AEKeyLinkedOpenHashMap(6);

        private Builder() {
        }

        public void addTo(AEKey key, long amount) {
            long previous = values.getLong(key);
            if (amount > 0 && previous > Long.MAX_VALUE - amount || amount < 0 && previous < Long.MIN_VALUE - amount)
                throw new ArithmeticException("Resource snapshot amount overflow");
            values.addTo(key, amount);
        }

        /**
         * Neither this builder nor the returned map may be modified after this handoff.
         */
        public AEKey2LongMap build() {
            return values.isEmpty() ? EMPTY : values;
        }
    }
}
