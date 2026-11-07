package com.circulation.prototype_eng.api.recipe.requirement;

/**
 * A resumable pure calculation for one current requirement. The object may contain independent
 * quantities, AEKey identities, immutable definitions, a private RNG and pure matcher/comparator
 * bindings. It must never retain a process, attributes, World, TE, storage/context, permission or
 * transaction. One worker owns it until physical exit; the owner reads results only after that join.
 * Every internal traversal/draw must consume the supplied shared slice before performing work.
 * Arbitrary external callbacks remain cooperative; the framework cannot preempt their internals.
 */
public interface RecipePureComputation {
    /**
     * False retains the cursor and every partial value privately; no acquisition may consume it.
     */
    boolean advance(RecipeWorkBudget budget);
}
