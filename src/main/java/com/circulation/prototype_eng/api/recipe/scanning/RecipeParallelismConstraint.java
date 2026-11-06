package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;

/**
 * Explicit whole-recipe scan constraints. Bounds must exclude no feasible base amount, and checks
 * must be pure; they must aggregate shared sources/requirements rather than reuse balances per entry.
 * An accepted constraint never replaces the mandatory transactional START probe.
 */
public interface RecipeParallelismConstraint {
    default int upperBound(MachineInstance machine, MachineRecipe recipe, int currentLimit) { return currentLimit; }
    default ProcessResult test(RecipeProcess process) { return ProcessResult.SUCCESS; }
}
