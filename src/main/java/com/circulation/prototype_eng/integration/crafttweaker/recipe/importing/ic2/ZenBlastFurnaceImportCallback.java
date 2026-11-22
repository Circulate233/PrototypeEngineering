package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.data.IData;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.BlastFurnaceCallback")
@FunctionalInterface
public interface ZenBlastFurnaceImportCallback {
    boolean create(ZenRecipeBuilder recipe, IData metadata, int requiredHeat);
}
