package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;

/**
 * 本次事件的可写数据。资源按对象身份索引，累加必须使用 addTo，禁止在回调结束后修改。
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

    public void validate() {
        NetworkResourceModifier.validateResources(resources);
        NetworkResourceModifier.validateResources(required);
        NetworkResourceModifier.validateResources(researchRates);
        if (concurrency < 0 || !Double.isFinite(energyMultiplier) || energyMultiplier < 0)
            throw new IllegalArgumentException("Invalid network concurrency or energy multiplier");
    }

    public static long energy(long amount, double multiplier) {
        if (multiplier == 1 || amount == 0) return amount;
        double result = Math.ceil(amount * multiplier);
        if (!Double.isFinite(result) || result >= 0x1p63 || result < 0)
            throw new ArithmeticException("Network energy cost overflow");
        return (long) result;
    }
}
