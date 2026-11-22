package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.integration.recipeimport.ic2.Ic2RecipeContext;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.data.IData;
import crafttweaker.mc1120.data.NBTConverter;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.Ic2Context")
public final class ZenIc2ImportContext {
    private final Ic2RecipeContext value;

    public ZenIc2ImportContext(Ic2RecipeContext value) {
        this.value = value;
    }

    @ZenGetter("group")
    public String group() {
        return value.group();
    }

    @ZenGetter("sourceIndex")
    public int sourceIndex() {
        return value.sourceIndex();
    }

    @ZenGetter("inputAmount")
    public int inputAmount() {
        return value.source().getInput().getAmount();
    }

    @ZenGetter("metadata")
    public IData metadata() {
        return NBTConverter.from(value.metadata().copy(), true);
    }
}
