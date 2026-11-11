package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponent;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Fixed scripts and Java-provided dynamic suppliers share the same canonical item registry. */
@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputerComponent")
@SuppressWarnings("unused") // Entry points are called by ZenScript and Java addons.
public record ZenComputerComponent(NetworkComputerComponent value) {
 public ZenComputerComponent { Objects.requireNonNull(value); }
    @ZenMethod public static ZenComputerComponent fixed(ZenComputingResources resources) {
        return new ZenComputerComponent(NetworkComputerComponent.fixed(Objects.requireNonNull(resources).value()));
    }
    @ZenMethod public void register(IItemStack item) {
        var stack = CraftTweakerMC.getItemStack(Objects.requireNonNull(item)).copy();
        DefinitionRegistrations.computerComponents(event -> event.registry().register(stack, value));
    }
}
