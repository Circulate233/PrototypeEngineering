package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.Objects;

/**
 * Ordered alternatives for one input; the first satisfiable key is locked by the requirement owner.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record FuzzyInputGroup(List<AEKey> candidates, long amount) {
    public FuzzyInputGroup {
        if (amount <= 0) {
            throw new IllegalArgumentException("Fuzzy input amount must be positive");
        }
        Objects.requireNonNull(candidates, "candidates");
        var copy = new ObjectArrayList<AEKey>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            copy.add(Objects.requireNonNull(candidate, "candidate"));
        }
        // An empty group deliberately cannot be satisfied, for disabled selector alternatives.
        candidates = ObjectLists.unmodifiable(copy);
    }
}
