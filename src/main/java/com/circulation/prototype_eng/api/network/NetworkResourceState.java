package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;

/**
 * Mutable event values. Resource keys use object identity; accumulate with addTo and stop modifying after the callback returns.
 */
public final class NetworkResourceState {
    public final Reference2DoubleOpenHashMap<NetworkResourceType> resources;
    public final Reference2DoubleOpenHashMap<NetworkResourceType> required;
    public final Reference2DoubleOpenHashMap<NetworkResourceType> researchRates = new Reference2DoubleOpenHashMap<>(0);
    public long concurrency;
    public double energyMultiplier = 1;

    public NetworkResourceState(Reference2DoubleMap<NetworkResourceType> provided, Reference2DoubleMap<NetworkResourceType> required, long concurrency) {
        this.resources = new Reference2DoubleOpenHashMap<>(provided);
        this.required = new Reference2DoubleOpenHashMap<>(required);
        this.concurrency = concurrency;
    }

    public NetworkResourceState copy() {
        var result = new NetworkResourceState(resources, required, concurrency);
        result.researchRates.putAll(researchRates);
        result.energyMultiplier = energyMultiplier;
        return result;
    }

    public static long energy(long amount, double multiplier) {
        if (multiplier == 1 || amount == 0) return amount;
        double result = Math.ceil(amount * multiplier);
        if (!Double.isFinite(result) || result >= 0x1p63 || result < 0)
            throw new ArithmeticException("Network energy cost overflow");
        return (long) result;
    }
}
