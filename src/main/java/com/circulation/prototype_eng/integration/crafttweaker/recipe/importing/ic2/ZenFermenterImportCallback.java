package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.FermenterCallback")
@FunctionalInterface
public interface ZenFermenterImportCallback {
    boolean create(ZenRecipeBuilder recipe, int heat, int biomassPerFertilizer, IItemStack fertilizer);
}
