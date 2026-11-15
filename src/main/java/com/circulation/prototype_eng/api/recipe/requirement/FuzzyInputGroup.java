package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.AEKey;

import java.util.List;

/**
 * Ordered alternatives for one input; the first satisfiable key is locked by the requirement owner.
 * The supplied list and its entries must remain unchanged for the definition's lifetime.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record FuzzyInputGroup(List<AEKey> candidates, long amount) {
    public FuzzyInputGroup {
        if (amount <= 0) {
            throw new IllegalArgumentException("Fuzzy input amount must be positive");
        }
    }
}
