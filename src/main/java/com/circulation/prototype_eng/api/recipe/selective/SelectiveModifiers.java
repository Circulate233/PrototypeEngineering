package com.circulation.prototype_eng.api.recipe.selective;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import net.minecraft.util.ResourceLocation;

/**
 * Shareable speed presets bound directly to descriptors for a recipe's selector declaration.
 * Construction validates the numeric modifier; application uses the selector's tracked put, so
 * START rollback, END cleanup and process persistence use the existing ownership and numeric codec.
 */
public final class SelectiveModifiers {
    private SelectiveModifiers() {
    }

    /**
     * Adds an amount to process speed when the selected candidate's START commits.
     */
    public static SelectiveModifierDescriptor processSpeedAddition(ResourceLocation id, String modifierId, double amount) {
        return processSpeed(id, speedAddition(modifierId, amount));
    }

    /**
     * Multiplies current process speed by the factor, using PM's MULTIPLY_TOTAL delta of factor - 1.
     * Zero and negative factors are permitted, matching the context's multiplier operation.
     */
    public static SelectiveModifierDescriptor processSpeedMultiplier(ResourceLocation id, String modifierId, double factor) {
        return processSpeed(id, speedMultiplier(modifierId, factor));
    }

    static NumericAttributeModifier speedAddition(String modifierId, double amount) {
        return new NumericAttributeModifier(modifierId, amount, AttributeModifier.Operation.ADDITION);
    }

    static NumericAttributeModifier speedMultiplier(String modifierId, double factor) {
        return new NumericAttributeModifier(modifierId, factor - 1, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    private static SelectiveModifierDescriptor processSpeed(ResourceLocation id, NumericAttributeModifier modifier) {
        return new SelectiveModifierDescriptor(id, context -> context.putProcessAttributeModifier(StandardMachineAttributes.PROCESS_SPEED, modifier));
    }
}
