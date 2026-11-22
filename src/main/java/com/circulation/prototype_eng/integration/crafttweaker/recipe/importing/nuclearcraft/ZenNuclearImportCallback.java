package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import crafttweaker.api.data.IData;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.NuclearCallback")
@FunctionalInterface
public interface ZenNuclearImportCallback {
    boolean create(ZenRecipeBuilder recipe, double durationTicks, double energyPerTick, double radiation, IData[] extras);
}
