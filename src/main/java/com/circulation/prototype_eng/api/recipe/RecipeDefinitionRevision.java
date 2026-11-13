package com.circulation.prototype_eng.api.recipe;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Declared behavior identity; revisions are not inferred from object hashes or server generations.
 */
public record RecipeDefinitionRevision(ResourceLocation recipeId, String revision) {
    public RecipeDefinitionRevision {
        Objects.requireNonNull(recipeId, "recipe ID");
        if (Objects.requireNonNull(revision, "revision").isBlank()) {
            throw new IllegalArgumentException("Recipe revision must not be blank");
        }
    }
}
