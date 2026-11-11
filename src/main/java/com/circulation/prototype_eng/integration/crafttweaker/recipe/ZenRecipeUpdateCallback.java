package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused") // ZenScript compiles this SAM.
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.UpdateCallback")
@FunctionalInterface
public interface ZenRecipeUpdateCallback {
    @ZenMethod
    void completed(ZenRecipeUpdateResult result);
}
