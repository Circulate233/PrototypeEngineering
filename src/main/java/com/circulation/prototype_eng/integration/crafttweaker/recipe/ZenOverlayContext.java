package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayContext;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import crafttweaker.annotations.ZenRegister;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;


// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.OverlayContext")
public final class ZenOverlayContext {
    private final RecipeOverlayContext context;

    public ZenOverlayContext(RecipeOverlayContext context) {
        this.context = context;
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
        putResourcePatch(target, layerId, inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs ? 1 : 0);
    }

    /**
     * Null inherits a resource list; an empty array clears it. The output option uses -1 to
     * inherit, 0 for false, and 1 for true.
     */
    @ZenMethod
    public void putResourcePatch(ZenRequirement target, String layerId, @Nullable ZenResource[] inputs,
                                 @Nullable ZenResource[] inputsPerTick, @Nullable ZenResource[] outputsPerTick,
                                 @Nullable ZenResource[] outputs, int voidExcessOutputs) {
        context.put(target.resource(), layerId, RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(ZenRecipeSupport.optionalStacks(inputs), ZenRecipeSupport.optionalStacks(inputsPerTick),
                ZenRecipeSupport.optionalStacks(outputsPerTick), ZenRecipeSupport.optionalStacks(outputs), voidExcessOutputs));
    }

    @ZenMethod
    public void putEnergyRates(ZenRequirement target, String layerId, long inputPerTick, long outputPerTick, boolean voidExcessOutputs) {
        putEnergyPatch(target, layerId, inputPerTick, outputPerTick, voidExcessOutputs ? 1 : 0);
    }

    /**
     * -1 inherits a rate; zero disables it. The output option uses -1 to inherit, 0 for false,
     * and 1 for true.
     */
    @ZenMethod
    public void putEnergyPatch(ZenRequirement target, String layerId, long inputPerTick,
                               long outputPerTick, int voidExcessOutputs) {
        context.put(target.energy(), layerId, RecipeOverlayTypes.ENERGY_RATES,
            new EnergyRequirementPatch(inputPerTick, outputPerTick, voidExcessOutputs));
    }

    @ZenMethod
    public void putOverlay(ZenRequirementOverlay overlay, String layerId) {
        overlay.put(context, layerId);
    }

    @ZenGetter("data")
    public ZenComponentData data() {
        var value = context.process().components().get(DataComponents.PROCESS);
        if (value == null) throw new IllegalStateException("Process data component was not declared");
        return new ZenComponentData(value);
    }
}
