package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.ElectrolyzerCallback")
@FunctionalInterface
public interface ZenElectrolyzerImportCallback {
    boolean create(ZenRecipeBuilder recipe, int energyPerTickEu, String[] outputDirections);
}
