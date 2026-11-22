package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.tconstruct;

import com.circulation.prototype_eng.integration.recipeimport.tconstruct.TConstructMeltingSource.MeltingContext;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.tconstruct.MeltingContext")
public final class ZenMeltingImportContext {
    private final MeltingContext value;

    public ZenMeltingImportContext(MeltingContext value) {
        this.value = value;
    }

    @ZenGetter("temperature")
    public int temperature() {
        return value.temperature();
    }

    @ZenGetter("usableTemperature")
    public int usableTemperature() {
        return value.usableTemperature();
    }
}
