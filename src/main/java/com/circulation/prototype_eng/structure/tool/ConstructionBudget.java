package com.circulation.prototype_eng.structure.tool;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.List;
import java.util.function.LongSupplier;

final class ConstructionBudget {
    private final LongSupplier nanoTime;
    private final IntArrayList eligible = new IntArrayList();
    private int nextStart;

    ConstructionBudget(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    <T> void run(long tickStart, long budgetNanos, List<T> runnable, Work<T> work) {
        int count = runnable.size();
        if (count == 0) {
            nextStart = 0;
            return;
        }
        int index = nextStart % count;
        nextStart = (index + 1) % count;
        eligible.clear();
        for (int i = 0; i < count; i++) {
            eligible.add(index);
            if (++index == count) index = 0;
        }
        long deadline = tickStart + budgetNanos;
        while (count > 0) {
            long now = nanoTime.getAsLong();
            long remaining = deadline - now;
            if (remaining <= 0) return;
            long share = Math.max(1L, remaining / count);
            int again = 0;
            for (int i = 0; i < count; i++) {
                long allowance = Math.min(share, deadline - now);
                if (allowance <= 0) return;
                index = eligible.getInt(i);
                if (work.run(runnable.get(index), new Slice(nanoTime, now + allowance))) eligible.set(again++, index);
                now = nanoTime.getAsLong();
            }
            count = again;
        }
    }

    @FunctionalInterface
    interface Work<T> {
        /** Returns true when the task can advance again within this tick. */
        boolean run(T task, Slice slice);
    }

    record Slice(LongSupplier nanoTime, long deadlineNanos) {
        boolean available() {
            return nanoTime.getAsLong() - deadlineNanos < 0;
        }
    }
}
