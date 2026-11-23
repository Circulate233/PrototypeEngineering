package com.circulation.prototype_eng.structure.tool;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;

final class ConstructionProgress {
    private final IntArrayFIFOQueue priority = new IntArrayFIFOQueue();
    private final IntArrayFIFOQueue ordinary = new IntArrayFIFOQueue();
    private int done;
    private boolean waiting;

    ConstructionProgress() {
    }

    ConstructionProgress(int size) {
        this(size, _ -> false);
    }

    ConstructionProgress(int size, IntPredicate first) {
        for (int index = 0; index < size; index++) add(index, first.test(index));
    }

    void add(int index, boolean first) {
        (first ? priority : ordinary).enqueue(index);
    }

    boolean firstPass() {
        return !priority.isEmpty();
    }

    void advance(int budget, IntFunction<Step> operation) {
        advance(null, budget, operation);
    }

    void advance(ConstructionBudget.Slice slice, IntFunction<Step> operation) {
        advance(slice, Integer.MAX_VALUE, operation);
    }

    private void advance(ConstructionBudget.@Nullable Slice slice, int budget, IntFunction<Step> operation) {
        waiting = false;
        if (firstPass()) {
            budget -= advancePhase(priority, slice, budget, operation);
            if (firstPass() || waiting || budget <= 0) return;
        }
        advancePhase(ordinary, slice, budget, operation);
    }

    private int advancePhase(IntArrayFIFOQueue queue, ConstructionBudget.@Nullable Slice slice, int budget, IntFunction<Step> operation) {
        int visits = Math.min(queue.size(), budget);
        for (int visit = 0; visit < visits && (slice == null || slice.available()); visit++) {
            int index = queue.firstInt();
            var result = operation.apply(index);
            if (result == Step.WAIT) {
                waiting = true;
                return visit + 1;
            }
            queue.dequeueInt();
            switch (result) {
                case COMPLETE -> done++;
                case RETRY -> queue.enqueue(index);
                case DEFER -> ordinary.enqueue(index);
                case WAIT -> throw new IllegalStateException("Waiting must preserve the current position");
            }
        }
        return visits;
    }

    int done() {
        return done;
    }

    boolean waiting() {
        return waiting;
    }

    enum Step {COMPLETE, RETRY, WAIT, DEFER}
}
