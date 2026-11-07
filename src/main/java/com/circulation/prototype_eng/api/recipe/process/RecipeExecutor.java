package com.circulation.prototype_eng.api.recipe.process;

/**
 * External responsibility for driving one exclusively owned recipe process for a machine tick.
 * Execution, including all process callbacks and resource settlement, must finish before returning.
 * The host invokes the selected executor once and does not advance the process again afterwards.
 */
@FunctionalInterface
public interface RecipeExecutor {
    /** Built-in process lifecycle, also supported by the host's explicit staged batch entry. */
    RecipeExecutor DEFAULT = RecipeProcess::tick;

    void tick(RecipeProcess process);
}
