package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

/**
 * PM's weighted output entries: select up to pickCount distinct entries on each execution.
 * Supplied entries must stay unchanged; a new list is created only to remove nonpositive weights.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record RandomOutputPool(List<WeightedSampling.Weighted<GenericStack>> candidates, int pickCount) {
    public RandomOutputPool {
        if (pickCount < 0) {
            throw new IllegalArgumentException("Negative random output pick count");
        }
        ObjectArrayList<WeightedSampling.Weighted<GenericStack>> filtered = null;
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            var stack = candidate.value();
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Random outputs need positive quantities");
            }
            if (candidate.weight() <= 0) {
                if (filtered == null) {
                    filtered = new ObjectArrayList<>(candidates.size());
                    for (int previous = 0; previous < index; previous++) filtered.add(candidates.get(previous));
                }
            } else if (filtered != null) {
                filtered.add(candidate);
            }
        }
        if (filtered != null) candidates = filtered;
    }

    public boolean hasOutputs() {
        return pickCount > 0 && !candidates.isEmpty();
    }
}
