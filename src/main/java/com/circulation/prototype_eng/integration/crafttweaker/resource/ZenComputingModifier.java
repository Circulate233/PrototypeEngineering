package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;


@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputingModifier")
@SuppressWarnings("unused") // Entry points are called by ZenScript and Java addons.
public final class ZenComputingModifier {
    private final NetworkResourceModifier.Builder builder = NetworkResourceModifier.builder();

    @ZenMethod
    public static ZenComputingModifier create() {
        return new ZenComputingModifier();
    }

    @ZenMethod
    public ZenComputingModifier flat(ZenComputingResource resource, double amount) {
        builder.flat(resource.value(), amount);
        return this;
    }

    @ZenMethod
    public ZenComputingModifier multiplierPpm(ZenComputingResource resource, double amount) {
        builder.multiplierPpm(resource.value(), amount);
        return this;
    }

    public NetworkResourceModifier value() {
        return builder.build();
    }
}
