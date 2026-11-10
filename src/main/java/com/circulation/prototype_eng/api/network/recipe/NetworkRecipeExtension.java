package com.circulation.prototype_eng.api.network.recipe;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;

/**
 * External loading-time callback; receives the canonical recipe and its add-only network demands.
 */
@FunctionalInterface
public interface NetworkRecipeExtension {
    void extend(MachineRecipe recipe, NetworkWorkRequirements.Builder requirements);
}
