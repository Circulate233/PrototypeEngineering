package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.tconstruct;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.tconstruct.MeltingCallback")
@FunctionalInterface
public interface ZenMeltingImportCallback {
    boolean create(ZenRecipeBuilder recipe, int temperature, int usableTemperature);
}
