package com.circulation.prototype_eng.api.recipe.overlay;

/** Real external instructions for writing process-owned requirement layers through a tracked context. */
@FunctionalInterface
public interface RecipeOverlayModifier {
    void apply(RecipeOverlayContext context);
}
