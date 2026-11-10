package com.circulation.prototype_eng.api.recipe.overlay;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * A stable name bound to a modifier implementation, without a replaceable global registry.
 */
public record RecipeOverlayModifierDescriptor(ResourceLocation id, RecipeOverlayModifier modifier) {
    public RecipeOverlayModifierDescriptor {
        Objects.requireNonNull(id);
        Objects.requireNonNull(modifier);
    }
}
