package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Stable bottom-up merge traversal; comparisons and moves retain their exact position.
 * The input list is owned scratch space; callers may consume it only after sorting completes.
 */
class PureMergeSort<T> {
    private final BiFunction<T, T, Comparison> comparison;
    private ObjectArrayList<T> values;
    private ObjectArrayList<T> buffer;
    private int written;
    private int width = 1;
    private int block;
    private int left;
    private int right;
    private int leftEnd;
    private int rightEnd;
    private boolean merging;
    private boolean compared;
    @Nullable
    private Comparison current;

    PureMergeSort(ObjectArrayList<T> values, BiFunction<T, T, Comparison> comparison) {
        this.values = values;
        this.comparison = comparison;
        buffer = new ObjectArrayList<>(values.size());
        buffer.size(values.size());
    }

    boolean advance(RecipeWorkBudget budget) {
        while (width < values.size()) {
            if (!merging) {
                if (block >= values.size()) {
                    var previous = values;
                    values = buffer;
                    buffer = previous;
                    block = 0;
                    written = 0;
                    width = width > values.size() / 2 ? values.size() : width * 2;
                    continue;
                }
                left = block;
                leftEnd = (int) Math.min(values.size(), (long) block + width);
                right = leftEnd;
                rightEnd = (int) Math.min(values.size(), (long) block + 2L * width);
                merging = true;
            }
            while (left < leftEnd || right < rightEnd) {
                if (left < leftEnd && right < rightEnd) {
                    if (current == null) {
                        current = comparison.apply(values.get(left), values.get(right));
                    }
                    if (!compared && !current.advance(budget)) {
                        return false;
                    }
                    compared = true;
                }
                if (!budget.take()) {
                    return false;
                }
                if (right == rightEnd || left < leftEnd && current != null && current.result() <= 0) {
                    buffer.set(written++, values.get(left++));
                } else {
                    buffer.set(written++, values.get(right++));
                }
                current = null;
                compared = false;
            }
            block = rightEnd;
            merging = false;
        }
        return true;
    }

    List<T> result() {
        return values;
    }

    interface Comparison {
        boolean advance(RecipeWorkBudget budget);

        int result();
    }

    static class StringComparison implements Comparison {
        private final String first;
        private final String second;
        private int index;
        private int order;
        private boolean complete;

        StringComparison(String first, String second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (complete) {
                return true;
            }
            if (first == null || second == null) {
                order = first == null && second == null ? 0 : first == null ? -1 : 1;
                complete = true;
                return true;
            }
            while (index < first.length() && index < second.length()) {
                if (!budget.take()) {
                    return false;
                }
                order = first.charAt(index) - second.charAt(index);
                index++;
                if (order != 0) {
                    complete = true;
                    return true;
                }
            }
            order = first.length() - second.length();
            complete = true;
            return true;
        }

        @Override
        public int result() {
            return order;
        }
    }
}
