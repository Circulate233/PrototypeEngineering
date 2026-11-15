package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;


@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputingResource")
@SuppressWarnings("unused")
public record ZenComputingResource(NetworkResourceType value) {

    @ZenMethod
    public static ZenComputingResource network(String id, String translationKey, String pattern) {
        return new ZenComputingResource(NetworkResourceType.network(new ResourceLocation(id), () -> translationKey, pattern));
    }

    @ZenMethod
    public static ZenComputingResource computer(String id, String translationKey, String pattern) {
        return new ZenComputingResource(NetworkResourceType.computer(new ResourceLocation(id), () -> translationKey, pattern));
    }

    @ZenGetter("internal")
    public boolean internal() {
        return value.internal();
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

}
