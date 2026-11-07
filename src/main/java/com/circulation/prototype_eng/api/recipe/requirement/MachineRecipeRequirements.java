package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;

import java.util.List;

/**
 * Machine component supplying additional canonical requirement roots for a real recipe process.
 * Return immutable baseline definitions, never effective views or quantities scaled for a particular
 * candidate. The process freezes the supplied roots before scanning sets its local parallelism.
 * Runtime choices belong to process components and transactions. Providers must not mutate assembly
 * or publish effects during capture, and must be installed before restoring work slots that use them.
 * Incompatible definition or behavior changes require the host's machine/recipe persistence revision
 * to change; existing processes retain their original frozen definitions until their lifecycle ends.
 */
@FunctionalInterface
public interface MachineRecipeRequirements {
    List<RecipeRequirement<?>> requirementsFor(MachineRecipe recipe);
}
