package com.circulation.prototype_eng.api.recipe.overlay;

/**
 * Transforms an immutable requirement payload using immutable overlay data. Implementations must be
 * pure: do not change the base, data, process, world or any other overlay. Both payload and data may
 * be shared by different processes. Structural child identities must remain stable while executing.
 */
@FunctionalInterface
public interface RecipeOverlayApplier<C, D> {
    C apply(C base, D data);
}
