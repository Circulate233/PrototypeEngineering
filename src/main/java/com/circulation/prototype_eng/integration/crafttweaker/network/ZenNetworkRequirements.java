package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.recipe.NetworkWorkRequirements;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResource;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;


@ZenRegister
@ZenClass("mods.prototype_eng.network.NetworkRequirements")
@SuppressWarnings("unused") // Script parameter conversion delegates to public Java contracts.
public final class ZenNetworkRequirements {
    private final NetworkWorkRequirements.Builder builder;
    private NetworkWorkRequirements captured;

    public ZenNetworkRequirements(NetworkWorkRequirements.Builder builder) {
        this.builder = builder;
    }

    @ZenMethod
    public static ZenNetworkRequirements create() {
        return new ZenNetworkRequirements(NetworkWorkRequirements.builder());
    }

    @ZenMethod
    public ZenNetworkRequirements research(String id, int minimumLevel) {
        builder.research(new ResourceLocation(id), minimumLevel);
        return this;
    }

    @ZenMethod
    public ZenNetworkRequirements resource(ZenComputingResource resource, double amountPerWorkUnit) {
        builder.resource(resource.value(), amountPerWorkUnit);
        return this;
    }

    /**
     * Captures this exact recipe object; same-name replacements require their own declaration.
     */
    @ZenMethod
    public void bind(ZenRecipe recipe, int priority) {
        var target = recipe.recipe();
        var demands = value();
        DefinitionRegistrations.recipes(event -> event.networkExtensions().register(priority, (candidate, context) -> {
            if (candidate == target) context.addAll(demands);
        }));
    }

    public NetworkWorkRequirements value() {
        if (captured == null) captured = builder.build();
        return captured;
    }
}
