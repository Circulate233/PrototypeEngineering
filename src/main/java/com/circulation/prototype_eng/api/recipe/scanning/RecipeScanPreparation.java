package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;

/**
 * Projects reversible process-local state needed for the first START of a fresh scan candidate.
 * Extensions may reproduce the local initialization normally performed by their first component
 * callbacks. Preparation must have no world effects and must return a handle with empty reservations.
 * Its acquisition, including any partial failure, follows the normal reversible transaction contract.
 * The handle is only rolled back, never committed or completed. Admission retains the factory's
 * original local state so the actual first component callbacks apply their initialization once.
 */
@FunctionalInterface
public interface RecipeScanPreparation {
    RequirementTransaction prepare(RecipeProcess process);
}
