package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.tconstruct;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.tconstruct.CastingCallback")
@FunctionalInterface
public interface ZenCastingImportCallback {
    boolean create(ZenRecipeBuilder recipe, boolean consumesCast, boolean switchOutputs);
}
