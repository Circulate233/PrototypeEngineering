package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/** Stable typed applier binding, retained directly instead of looking up a replaceable global registry. */
public record RecipeOverlayType<C, D>(ResourceLocation id, RecipeRequirementType<C> requirementType,
                                      RecipeOverlayApplier<C, D> applier) {
    public RecipeOverlayType {
        Objects.requireNonNull(id);
        Objects.requireNonNull(requirementType);
        Objects.requireNonNull(applier);
    }
}
