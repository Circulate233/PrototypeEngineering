package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.minecraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.minecraft.FurnaceCallback")
@FunctionalInterface
public interface ZenFurnaceImportCallback {
    boolean create(ZenRecipeBuilder recipe, float experience);
}
