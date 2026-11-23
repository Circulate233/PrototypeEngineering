package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.BuilderConfig;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

final class ConstructionBudget {
    static final long TICK_NANOS = BuilderConfig.TICK_NANOS;
    private final LongSupplier nanoTime;
    private int nextStart;

    ConstructionBudget(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    <T> Report run(List<T> runnable, BiConsumer<T, Slice> work) {
        return run(nanoTime.getAsLong(), runnable, work);
    }

    <T> Report run(long tickStart, List<T> runnable, BiConsumer<T, Slice> work) {
        int count = runnable.size();
        if (count == 0) {
            nextStart = 0;
            return new Report(0, 0);
        }
        int index = nextStart % count;
        nextStart = (index + 1) % count;
        long share = (TICK_NANOS - (nanoTime.getAsLong() - tickStart)) / count;
        if (share <= 0L) return new Report(0, 0);
        int overruns = 0;
        long maxOverrun = 0;
        for (int served = 0; served < count; served++) {
            long start = nanoTime.getAsLong();
            long allowed = Math.min(share, TICK_NANOS - (start - tickStart));
            if (allowed <= 0L) break;
            var slice = new Slice(nanoTime, start, allowed);
            work.accept(runnable.get(index), slice);
            long overrun = nanoTime.getAsLong() - start - allowed;
            if (overrun > 0L) {
                overruns++;
                maxOverrun = Math.max(maxOverrun, overrun);
            }
            if (++index == count) index = 0;
        }
        return new Report(overruns, maxOverrun);
    }

    record Slice(LongSupplier nanoTime, long start, long allowanceNanos) {
        boolean available() {
            return nanoTime.getAsLong() - start < allowanceNanos;
        }
    }

    record Report(int overruns, long maxOverrunNanos) {
    }
}
