package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveContext;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import crafttweaker.annotations.ZenRegister;
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
        context.putProcessAttributeModifier(ZenRecipeSupport.id(attribute),
            new NumericAttributeModifier(modifierId, amount, AttributeModifier.Operation.ADDITION));
    }

    @ZenMethod
    public void putResourceIO(ZenRequirement target, String layerId, ZenResource[] inputs, ZenResource[] inputsPerTick,
                              ZenResource[] outputsPerTick, ZenResource[] outputs, boolean voidExcessOutputs) {
        context.putRequirementOverlay(Objects.requireNonNull(target).resource(), layerId, RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(ZenRecipeSupport.stacks(inputs), ZenRecipeSupport.stacks(inputsPerTick),
                ZenRecipeSupport.stacks(outputsPerTick), ZenRecipeSupport.stacks(outputs), voidExcessOutputs));
    }

    @ZenMethod
    public void putEnergyRates(ZenRequirement target, String layerId, long inputPerTick, long outputPerTick, boolean voidExcessOutputs) {
        context.putRequirementOverlay(Objects.requireNonNull(target).energy(), layerId, RecipeOverlayTypes.ENERGY_RATES,
            new EnergyRequirementPatch(inputPerTick, outputPerTick, voidExcessOutputs));
    }

    /** Ordinary process data must have been explicitly included in the machine's process assembly. */
    @ZenGetter("data")
    public ZenComponentData data() {
        var value = context.process().components().get(DataComponents.PROCESS);
        if (value == null) throw new IllegalStateException("Process data component was not declared");
        return new ZenComponentData(value);
    }
}
