package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputingResource")
@SuppressWarnings("unused") // Entry points are called by ZenScript and Java addons.
public record ZenComputingResource(NetworkResourceType value) {
    public ZenComputingResource {
        Objects.requireNonNull(value);
    }

    @ZenMethod
    public static ZenComputingResource create(String id, boolean logicalConcurrency) {
        return new ZenComputingResource(new NetworkResourceType(new ResourceLocation(id), logicalConcurrency));
    }

    @ZenMethod
    public static ZenComputingResource thread() {
        return new ZenComputingResource(NetworkResources.THREAD);
    }

    @ZenMethod
    public static ZenComputingResource compute() {
        return new ZenComputingResource(NetworkResources.COMPUTE);
    }

    @ZenMethod
    public static ZenComputingResource qubit() {
        return new ZenComputingResource(NetworkResources.QUBIT);
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

    @ZenGetter("logicalConcurrency")
    public boolean logicalConcurrency() {
        return value.logicalConcurrency();
    }

    @ZenMethod
    public void register() {
        DefinitionRegistrations.resources(event -> event.registry().register(value));
    }
}
