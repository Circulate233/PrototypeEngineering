package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thaumcraft.InfusionCallback")
@FunctionalInterface
public interface ZenInfusionImportCallback {
    boolean create(ZenRecipeBuilder recipe, ZenInfusionImportContext context);
}
