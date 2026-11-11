package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputingModifier")
@SuppressWarnings("unused") // Entry points are called by ZenScript and Java addons.
public final class ZenComputingModifier {
    private final NetworkResourceModifier.Builder builder = NetworkResourceModifier.builder();
    @ZenMethod public static ZenComputingModifier create() { return new ZenComputingModifier(); }
    @ZenMethod public ZenComputingModifier flat(ZenComputingResource resource, long amount) {
        builder.flat(Objects.requireNonNull(resource).value(), amount); return this;
    }
    @ZenMethod public ZenComputingModifier multiplierPpm(ZenComputingResource resource, long amount) {
        builder.multiplierPpm(Objects.requireNonNull(resource).value(), amount); return this;
    }
    public NetworkResourceModifier value() { return builder.build(); }
}
