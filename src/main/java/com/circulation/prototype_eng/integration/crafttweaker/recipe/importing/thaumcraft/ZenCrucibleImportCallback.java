package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.data.IData;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thaumcraft.CrucibleCallback")
@FunctionalInterface
public interface ZenCrucibleImportCallback {
    boolean create(ZenRecipeBuilder recipe, String sourceId, IData aspects, String research);
}
