package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.data.IData;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.MultiblockElectrolyzerCallback")
@FunctionalInterface
public interface ZenMultiblockElectrolyzerImportCallback {
    boolean create(ZenRecipeBuilder recipe, double durationTicks, double energyPerTick, double radiation, IData[] extras, String electrolyteGroup);
}
