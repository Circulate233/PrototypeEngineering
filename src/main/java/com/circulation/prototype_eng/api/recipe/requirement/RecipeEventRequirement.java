package com.circulation.prototype_eng.api.recipe.requirement;

import java.util.Objects;

/**
 * Immutable handler binding shared by ordinary recipe and pool declarations.
 */
public record RecipeEventRequirement(RecipeStage stage, RecipeEventHandler handler) {
    public RecipeEventRequirement {
        Objects.requireNonNull(stage, "Recipe event stage");
        Objects.requireNonNull(handler, "Recipe event handler");
    }
}
