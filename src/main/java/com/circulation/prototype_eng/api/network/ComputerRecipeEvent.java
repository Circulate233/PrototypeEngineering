package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;

/**
 * Candidate changes apply after this stage commits its inputs. Do not retain values or mutate other devices.
 */
public record ComputerRecipeEvent(ComputerController computer, RecipeProcess process, RecipeStage stage,
                                  NetworkData network, NetworkResourceState values) {
}
