package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.Objects;

/** PM's weighted output entries: select up to pickCount distinct entries on each execution. */
public record RandomOutputPool(List<WeightedSampling.Weighted<GenericStack>> candidates, int pickCount) {
    public RandomOutputPool {
        if (pickCount < 0) {
            throw new IllegalArgumentException("Negative random output pick count");
        }
        var copy = new ObjectArrayList<WeightedSampling.Weighted<GenericStack>>();
        for (var candidate : candidates) {
            Objects.requireNonNull(candidate);
            var stack = Objects.requireNonNull(candidate.value());
            Objects.requireNonNull(stack.what());
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Random outputs need positive quantities");
            }
            if (candidate.weight() > 0) {
                copy.add(candidate);
            }
        }
        candidates = ObjectLists.unmodifiable(copy);
    }

    public boolean hasOutputs() {
        return pickCount > 0 && !candidates.isEmpty();
    }
}
