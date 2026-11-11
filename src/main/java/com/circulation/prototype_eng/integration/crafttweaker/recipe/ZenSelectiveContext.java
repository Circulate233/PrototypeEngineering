package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveContext;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import crafttweaker.annotations.ZenRegister;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.SelectiveContext")
public final class ZenSelectiveContext {
    private final SelectiveContext context;

    public ZenSelectiveContext(SelectiveContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @ZenGetter("candidateIndex")
    public int candidateIndex() {
        return context.candidateIndex();
    }

    @ZenGetter("selectionId")
    public String selectionId() {
        return context.selectionId();
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
    public void addProcessSpeed(String id, double amount) {
        context.addProcessSpeed(id, amount);
    }

    @ZenMethod
    public void mulProcessSpeed(String id, double factor) {
        context.mulProcessSpeed(id, factor);
    }

    @ZenMethod
    public void addAttribute(String attribute, String modifierId, double amount) {
        putAttribute(attribute, modifierId, amount, "ADDITION");
    }

    @ZenMethod
    public void putAttribute(String attribute, String modifierId, double amount, String operation) {
        context.putProcessAttributeModifier(ZenRecipeSupport.id(attribute),
            ZenRecipeSupport.numericModifier(modifierId, amount, operation));
    }

    @ZenMethod
    public void putResourceIO(ZenRequirement target, String layerId, ZenResource[] inputs, ZenResource[] inputsPerTick,
                              ZenResource[] outputsPerTick, ZenResource[] outputs, boolean voidExcessOutputs) {
        putResourcePatch(target, layerId, inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs);
    }

    /** Null inherits a field; an empty resource array explicitly clears it. */
    @ZenMethod
    public void putResourcePatch(ZenRequirement target, String layerId, @Nullable ZenResource[] inputs,
                                 @Nullable ZenResource[] inputsPerTick, @Nullable ZenResource[] outputsPerTick,
                                 @Nullable ZenResource[] outputs, @Nullable Boolean voidExcessOutputs) {
        context.putRequirementOverlay(Objects.requireNonNull(target).resource(), layerId, RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(ZenRecipeSupport.optionalStacks(inputs), ZenRecipeSupport.optionalStacks(inputsPerTick),
                ZenRecipeSupport.optionalStacks(outputsPerTick), ZenRecipeSupport.optionalStacks(outputs), voidExcessOutputs));
    }

    @ZenMethod
    public void putEnergyRates(ZenRequirement target, String layerId, long inputPerTick, long outputPerTick, boolean voidExcessOutputs) {
        putEnergyPatch(target, layerId, inputPerTick, outputPerTick, voidExcessOutputs);
    }

    @ZenMethod
    public void putEnergyPatch(ZenRequirement target, String layerId, @Nullable Long inputPerTick,
                               @Nullable Long outputPerTick, @Nullable Boolean voidExcessOutputs) {
        context.putRequirementOverlay(Objects.requireNonNull(target).energy(), layerId, RecipeOverlayTypes.ENERGY_RATES,
            new EnergyRequirementPatch(inputPerTick, outputPerTick, voidExcessOutputs));
    }

    @ZenMethod
    public void putOverlay(ZenRequirementOverlay overlay, String layerId) {
        Objects.requireNonNull(overlay).put(context, layerId);
    }

    /** Ordinary process data must have been explicitly included in the machine's process assembly. */
    @ZenGetter("data")
    public ZenComponentData data() {
        var value = context.process().components().get(DataComponents.PROCESS);
        if (value == null) throw new IllegalStateException("Process data component was not declared");
        return new ZenComponentData(value);
    }
}
