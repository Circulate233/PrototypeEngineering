package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.integration.recipeimport.thermalexpansion.ThermalInsolatorSource.InsolatorContext;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.InsolatorContext")
public final class ZenInsolatorImportContext {
    private final InsolatorContext value;

    public ZenInsolatorImportContext(InsolatorContext value) {
        this.value = value;
    }

    @ZenGetter("tree")
    public boolean tree() {
        return value.tree();
    }

    @ZenGetter("energy")
    public int energy() {
        return value.energy();
    }

    @ZenGetter("water")
    public int water() {
        return value.water();
    }

    @ZenGetter("secondaryChance")
    public int secondaryChance() {
        return value.secondaryChance();
    }

    @ZenGetter("hasFertilizer")
    public boolean hasFertilizer() {
        return value.hasFertilizer();
    }
}
