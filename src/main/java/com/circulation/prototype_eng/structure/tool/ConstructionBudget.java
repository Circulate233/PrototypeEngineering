package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.BuilderConfig;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

final class ConstructionBudget {
    static final long TICK_NANOS = BuilderConfig.TICK_NANOS;
    private static final LongSupplier NO_RESERVATION = () -> 0L;
    private final LongSupplier nanoTime;
    private int nextStart;

    ConstructionBudget(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    <T> void run(List<T> runnable, BiConsumer<T, Slice> work) {
        run(nanoTime.getAsLong(), runnable, work);
    }

    <T> void run(long tickStart, List<T> runnable, BiConsumer<T, Slice> work) {
        run(tickStart, runnable, NO_RESERVATION, work);
    }

    <T> void run(long tickStart, List<T> runnable, LongSupplier reservedNanos, BiConsumer<T, Slice> work) {
        int count = runnable.size();
        if (count == 0) {
            nextStart = 0;
            return;
        }
        int index = nextStart % count;
        nextStart = (index + 1) % count;
        long share = (TICK_NANOS - (nanoTime.getAsLong() - tickStart) - reservedNanos.getAsLong()) / count;
        if (share <= 0L) return;
        for (int served = 0; served < count; served++) {
            long start = nanoTime.getAsLong();
            long reserved = reservedNanos.getAsLong();
            long allowed = Math.min(share, TICK_NANOS - (start - tickStart) - reserved);
            if (allowed <= 0L) break;
            work.accept(runnable.get(index), new Slice(nanoTime, start, allowed, reservedNanos, reserved));
            if (++index == count) index = 0;
        }
    }

    record Slice(LongSupplier nanoTime, long start, long allowanceNanos, LongSupplier reservedNanos, long reservedAtStart) {
        boolean available() {
            return nanoTime.getAsLong() - start + (reservedNanos.getAsLong() - reservedAtStart) < allowanceNanos;
        }
    }
}
