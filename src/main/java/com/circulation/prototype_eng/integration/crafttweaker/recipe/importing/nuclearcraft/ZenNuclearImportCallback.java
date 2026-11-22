package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.NuclearCallback")
@FunctionalInterface
public interface ZenNuclearImportCallback {
    boolean create(ZenRecipeBuilder recipe, ZenNuclearImportContext context);
}
