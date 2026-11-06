package com.circulation.prototype_eng.api.recipe.probability;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Weighted draws without replacement of candidate entries, preserving PM's random-output semantics. */
public final class WeightedSampling {
    private WeightedSampling() {
    }

    /** Nonpositive weights are retained as data but ignored by sampling. */
    public record Weighted<T>(T value, int weight) {
        public Weighted {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Draws up to count positive-weight entries without changing the input list. Duplicate values in
     * separate entries may both be returned. Integer weights and List's int size bound make their
     * sum fit a long; an integer lottery avoids the precision loss of PM's double*total conversion.
     */
    public static <T> List<T> sampleWithoutReplacement(RandomGenerator random, List<Weighted<T>> candidates, int count) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(candidates, "candidates");
        if (count <= 0) {
            return ObjectLists.emptyList();
        }
        var pool = new ObjectArrayList<Weighted<T>>();
        long total = 0;
        for (var candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (candidate.weight() > 0) {
                pool.add(candidate);
                total += candidate.weight();
            }
        }
        int draws = Math.min(count, pool.size());
        var results = new ObjectArrayList<T>(draws);
        for (int draw = 0; draw < draws; draw++) {
            long ticket = random.nextLong(total);
            int selected = 0;
            while (ticket >= pool.get(selected).weight()) {
                ticket -= pool.get(selected).weight();
                selected++;
            }
            var candidate = pool.remove(selected);
            results.add(candidate.value());
            total -= candidate.weight();
        }
        return results;
    }
}
