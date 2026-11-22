package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.SmelterCallback")
@FunctionalInterface
public interface ZenSmelterImportCallback {
    boolean create(ZenRecipeBuilder recipe, int energy, int secondaryChance, boolean hasFlux);
}
