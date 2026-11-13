package com.circulation.prototype_eng.storage;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;

/**
 * Overflow-checked key-ratio projections of actual resource receipts.
 */
final class PatternInputSignature {
    private PatternInputSignature() {
    }

    static boolean add(AEKey2LongMap amounts, AEKey key, long amount) {
        if (amount <= 0 || amount > Long.MAX_VALUE - amounts.getLong(key)) return false;
        amounts.addTo(key, amount);
        return true;
    }

    /**
     * Positive receipt amounts are read-only; an already normalized map can be retained directly.
     */
    static AEKey2LongMap normalize(AEKey2LongMap amounts) {
        if (amounts.isEmpty()) return amounts;
        long divisor = 0;
        for (var entry : amounts.object2LongEntrySet()) {
            long value = entry.getLongValue();
            while (value != 0) {
                long rest = divisor % value;
                divisor = value;
                value = rest;
            }
            if (divisor == 1) return amounts;
        }
        var normalized = new AEKey2LongMap.OpenHashMap(amounts.size());
        if (divisor != 0) {
            long by = divisor;
            amounts.forEachEntry((key, value) -> normalized.put(key, value / by));
        }
        return normalized;
    }
}
