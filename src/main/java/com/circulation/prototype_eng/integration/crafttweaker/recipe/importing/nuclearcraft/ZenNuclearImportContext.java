package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.integration.recipeimport.nuclearcraft.NuclearCraftRecipeContext;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.NuclearContext")
public final class ZenNuclearImportContext {
    private final NuclearCraftRecipeContext value;

    public ZenNuclearImportContext(NuclearCraftRecipeContext value) {
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

    @ZenGetter("variantIndex")
    public int variantIndex() {
        return value.variantIndex();
    }

    @ZenGetter("durationTicks")
    public double durationTicks() {
        return value.durationTicks();
    }

    @ZenGetter("energyPerTick")
    public double energyPerTick() {
        return value.energyPerTick();
    }

    @ZenGetter("radiation")
    public double radiation() {
        return value.radiation();
    }

    @ZenGetter("extraCount")
    public int extraCount() {
        return value.extras().size();
    }

    @ZenMethod
    public String extraText(int index) {
        return String.valueOf(value.extras().get(index));
    }

    @ZenMethod
    public double extraNumber(int index) {
        var extra = value.extras().get(index);
        if (!(extra instanceof Number number))
            throw new IllegalArgumentException("NuclearCraft extra is not numeric: " + index);
        return number.doubleValue();
    }
}
