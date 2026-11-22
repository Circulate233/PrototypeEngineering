package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.Ic2Callback")
@FunctionalInterface
public interface ZenIc2ImportCallback {
    boolean create(ZenRecipeBuilder recipe, ZenIc2ImportContext context);
}
