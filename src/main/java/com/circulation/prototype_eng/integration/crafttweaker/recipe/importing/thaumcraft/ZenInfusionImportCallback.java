package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import crafttweaker.api.data.IData;
import crafttweaker.api.item.IItemStack;
import org.jetbrains.annotations.Nullable;

@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thaumcraft.InfusionCallback")
@FunctionalInterface
public interface ZenInfusionImportCallback {
    boolean create(ZenRecipeBuilder recipe, String sourceId, IData aspects, int instability, String research, @Nullable IItemStack mainInput);
}
