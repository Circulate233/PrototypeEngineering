package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.integration.recipeimport.thaumcraft.ThaumcraftInfusionSource.InfusionContext;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thaumcraft.InfusionContext")
public final class ZenInfusionImportContext {
    private final InfusionContext value;

    public ZenInfusionImportContext(InfusionContext value) {
        this.value = value;
    }

    @ZenGetter("sourceId")
    public String sourceId() {
        return value.sourceId().toString();
    }

    @ZenGetter("instability")
    public int instability() {
        return value.instability();
    }

    @ZenGetter("research")
    public String research() {
        return value.research();
    }

    @ZenGetter("aspectNames")
    public String[] aspectNames() {
        var aspects = value.aspects().getAspectsSortedByName();
        var names = new String[aspects.length];
        for (int i = 0; i < aspects.length; i++) names[i] = aspects[i].getTag();
        return names;
    }

    @ZenMethod
    public int aspectAmount(String name) {
        var aspects = value.aspects().getAspects();
        for (var aspect : aspects)
            if (aspect.getTag().equals(name)) return value.aspects().getAmount(aspect);
        return 0;
    }

    @ZenGetter("mainInput")
    public IItemStack mainInput() {
        return CraftTweakerMC.getIItemStack(value.mainInput().copy());
    }
}
