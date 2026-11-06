package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;

/** Fresh unpublished candidates only. Respect identity, seed and tuning, and publish no world effects. */
@FunctionalInterface
public interface RecipeProcessFactory {
    RecipeProcess create(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning);
}
