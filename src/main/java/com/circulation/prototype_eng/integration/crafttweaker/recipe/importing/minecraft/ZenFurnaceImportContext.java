package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.minecraft;

import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenResource;
import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.integration.recipeimport.minecraft.FurnaceRecipeSource.FurnaceContext;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.minecraft.FurnaceContext")
public final class ZenFurnaceImportContext {
    private final FurnaceContext value;

    public ZenFurnaceImportContext(FurnaceContext value) {
        this.value = value;
    }

    @ZenGetter("input")
    public ZenResource input() {
        return new ZenResource(AEItemKey.of(value.input()), 1);
    }

    @ZenGetter("output")
    public ZenResource output() {
        return new ZenResource(AEItemKey.of(value.output()), value.output().getCount());
    }

    @ZenGetter("experience")
    public float experience() {
        return value.experience();
    }
}
