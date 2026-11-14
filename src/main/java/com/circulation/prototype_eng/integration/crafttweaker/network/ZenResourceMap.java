package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResource;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.ResourceMap")
@SuppressWarnings("unused")
public record ZenResourceMap(Reference2DoubleOpenHashMap<NetworkResourceType> value) {
    @ZenMethod
    public double get(ZenComputingResource resource) {
        return value.getDouble(resource.value());
    }

    @ZenMethod
    public void set(ZenComputingResource resource, double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid resource amount");
        value.put(resource.value(), amount);
    }

    @ZenMethod
    public void add(ZenComputingResource resource, double amount) {
        double total = NetworkResourceModifier.sum(value.getDouble(resource.value()), amount);
        if (total < 0) throw new IllegalArgumentException("Negative resource amount");
        value.addTo(resource.value(), amount);
    }

    @ZenMethod
    public void remove(ZenComputingResource resource) {
        value.removeDouble(resource.value());
    }
}
