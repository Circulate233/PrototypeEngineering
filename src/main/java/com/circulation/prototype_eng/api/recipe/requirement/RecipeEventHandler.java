package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;

/**
 * Event acquisition follows the machine's execution policy. All effects must belong to the
 * returned reversible transaction. Admission probes do not invoke events; START effects needed
 * by admission must also be projected explicitly through RecipeScanPreparation.
 */
@FunctionalInterface
public interface RecipeEventHandler {
    RequirementTransaction acquire(RecipeProcess process, RecipeStage stage);
}
