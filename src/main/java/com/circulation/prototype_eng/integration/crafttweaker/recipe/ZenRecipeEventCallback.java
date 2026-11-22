package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.EventCallback")
@FunctionalInterface
public interface ZenRecipeEventCallback {
    ZenRecipeEventTransaction acquire(ZenRecipeEventContext context);
}
