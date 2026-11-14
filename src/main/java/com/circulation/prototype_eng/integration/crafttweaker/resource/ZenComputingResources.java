package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/**
 * Build the parameter vector before passing it to a definition; do not modify it afterward.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputingResources")
@SuppressWarnings("unused") // Entry points are called by ZenScript and Java addons.
public final class ZenComputingResources {
    private final Reference2DoubleOpenHashMap<NetworkResourceType> values = new Reference2DoubleOpenHashMap<>();

    @ZenMethod
    public static ZenComputingResources create() {
        return new ZenComputingResources();
    }

    @ZenMethod
    public ZenComputingResources add(ZenComputingResource resource, double amount) {
        var type = resource.value();
        if (!Double.isFinite(amount) || amount < 0)
            throw new IllegalArgumentException("Computing supply must be finite and nonnegative");
        NetworkResourceModifier.sum(amount, values.getDouble(type));
        values.addTo(type, amount);
        return this;
    }

    @ZenMethod
    public double amount(ZenComputingResource resource) {
        return values.getDouble(resource.value());
    }

    public Reference2DoubleMap<NetworkResourceType> value() {
        return values;
    }
}
