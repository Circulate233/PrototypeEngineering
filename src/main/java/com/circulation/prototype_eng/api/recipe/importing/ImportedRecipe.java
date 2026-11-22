package com.circulation.prototype_eng.api.recipe.importing;

import com.circulation.prototype_eng.api.recipe.RecipeBuilder;

import java.util.Objects;

/** One source conversion, edited and accepted independently by each importing pool. */
public record ImportedRecipe<C>(RecipeBuilder recipe, C context) {
    public ImportedRecipe {
        Objects.requireNonNull(recipe, "recipe");
    }
}
