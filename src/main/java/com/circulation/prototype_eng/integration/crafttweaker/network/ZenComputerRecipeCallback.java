package com.circulation.prototype_eng.integration.crafttweaker.network;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.network.ComputerRecipeCallback")
@FunctionalInterface
public interface ZenComputerRecipeCallback {
    void apply(ZenComputerRecipeEvent event);
}
