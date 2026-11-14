package com.circulation.prototype_eng.api.recipe.probability;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Weighted draws without replacement of candidate entries, preserving PM's random-output semantics.
 */
public final class WeightedSampling {
    private WeightedSampling() {
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
        var pool = new ObjectArrayList<Weighted<T>>(candidates.size());
        long total = 0;
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
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

    /**
     * Retains the exact lottery position and RNG across bounded slices, without pool shifts.
     */
    public static class Cursor<T> {
        private final RandomGenerator random;
        private final List<Weighted<T>> candidates;
        private final int count;
        private final ObjectArrayList<Weighted<T>> pool = new ObjectArrayList<>();
        private final ObjectArrayList<T> results = new ObjectArrayList<>();
        private int captured;
        private int draw;
        private int selected;
        private long total;
        private long ticket;
        private boolean drawing;
        private boolean complete;

        public Cursor(RandomGenerator random, List<Weighted<T>> candidates, int count) {
            this.random = Objects.requireNonNull(random);
            this.candidates = Objects.requireNonNull(candidates);
            this.count = Math.max(0, count);
        }

        public boolean advance(RecipeWorkBudget budget) {
            if (complete) {
                return true;
            }
            if (count == 0) {
                complete = true;
                return true;
            }
            while (captured < candidates.size()) {
                if (!budget.take()) {
                    return false;
                }
                var candidate = Objects.requireNonNull(candidates.get(captured++));
                if (candidate.weight() > 0) {
                    pool.add(candidate);
                    total += candidate.weight();
                }
            }
            int draws = Math.min(count, pool.size());
            while (draw < draws) {
                if (!drawing) {
                    if (!budget.take()) {
                        return false;
                    }
                    ticket = random.nextLong(total);
                    selected = 0;
                    drawing = true;
                }
                while (selected < pool.size()) {
                    if (!budget.take()) {
                        return false;
                    }
                    var candidate = pool.get(selected);
                    if (candidate == null) {
                        selected++;
                    } else if (ticket >= candidate.weight()) {
                        ticket -= candidate.weight();
                        selected++;
                    } else {
                        results.add(candidate.value());
                        pool.set(selected, null);
                        total -= candidate.weight();
                        draw++;
                        drawing = false;
                        break;
                    }
                }
            }
            complete = true;
            return true;
        }

        public List<T> result() {
            if (!complete) {
                throw new IllegalStateException("Weighted draw is incomplete");
            }
            return ObjectLists.unmodifiable(results);
        }
    }

    /**
     * Nonpositive weights are retained as data but ignored by sampling.
     */
    public record Weighted<T>(T value, int weight) {
        public Weighted {
            Objects.requireNonNull(value, "value");
        }
    }
}
