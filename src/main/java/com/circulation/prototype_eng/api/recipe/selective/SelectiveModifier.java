package com.circulation.prototype_eng.api.recipe.selective;

/**
 * Immutable, shareable instructions applied when a selected candidate's START commits. Reversible
 * attribute effects must use the context's tracked put operation. Direct mutations of other
 * process/machine state are outside the selector's compensation and future persistence boundary.
 */
@FunctionalInterface
public interface SelectiveModifier {
    void apply(SelectiveContext context);
}
