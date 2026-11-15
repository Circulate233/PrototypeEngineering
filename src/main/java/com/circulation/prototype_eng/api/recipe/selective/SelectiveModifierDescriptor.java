package com.circulation.prototype_eng.api.recipe.selective;

import net.minecraft.util.ResourceLocation;


/**
 * A stable name bound directly to immutable modifier instructions, without a replaceable global registry.
 */
public record SelectiveModifierDescriptor(ResourceLocation id, SelectiveModifier modifier) {
}
