package com.circulation.prototype_eng.recipe.requirement;

/**
 * Internal read-only view supplied to vanilla long-array tags by the project's Mixin.
 */
public interface NbtLongArrayAccess {
    int prototype_eng$length();

    long prototype_eng$value(int index);
}
