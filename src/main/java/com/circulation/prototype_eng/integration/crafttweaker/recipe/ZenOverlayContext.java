package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayContext;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.OverlayContext")
public final class ZenOverlayContext {
    private final RecipeOverlayContext context;

    public ZenOverlayContext(RecipeOverlayContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @ZenGetter("modifierId")
    public String modifierId() {
        return context.modifierId().toString();
    }

    @ZenGetter("modifierOrdinal")
    public int modifierOrdinal() {
        return context.modifierOrdinal();
    }

    @ZenMethod
    public void putResourceIO(ZenRequirement target, String layerId, ZenResource[] inputs, ZenResource[] inputsPerTick,
                              ZenResource[] outputsPerTick, ZenResource[] outputs, boolean voidExcessOutputs) {
        context.put(Objects.requireNonNull(target).resource(), layerId, RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(ZenRecipeSupport.stacks(inputs), ZenRecipeSupport.stacks(inputsPerTick),
                ZenRecipeSupport.stacks(outputsPerTick), ZenRecipeSupport.stacks(outputs), voidExcessOutputs));
    }

    @ZenMethod
    public void putEnergyRates(ZenRequirement target, String layerId, long inputPerTick, long outputPerTick, boolean voidExcessOutputs) {
        context.put(Objects.requireNonNull(target).energy(), layerId, RecipeOverlayTypes.ENERGY_RATES,
            new EnergyRequirementPatch(inputPerTick, outputPerTick, voidExcessOutputs));
    }

    @ZenGetter("data")
    public ZenComponentData data() {
        var value = context.process().components().get(DataComponents.PROCESS);
        if (value == null) throw new IllegalStateException("Process data component was not declared");
        return new ZenComponentData(value);
    }
}
