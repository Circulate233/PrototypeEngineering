package com.circulation.prototype_eng.api.recipe.matching;

import ae2.api.stacks.AEKey;

/**
 * Pure resource-key predicate. Implementations must not mutate keys or external state and must retain
 * the same matching behavior throughout a recipe/process lifetime, including parallel execution.
 */
@FunctionalInterface
public interface ResourceMatcher {
    boolean matches(AEKey candidate, AEKey pattern);
}
