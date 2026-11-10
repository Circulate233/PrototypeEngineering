package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.SchedulerReport;
import mcp.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Owner-thread bounded tick window. Recording never formats, sorts or publishes a report. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class SchedulerMetrics {
    private static final SchedulerReport.Stat EMPTY = new SchedulerReport.Stat(0, 0, 0);
    private static final int FIELD_COUNT = 12;
    private int windowTicks;
    private long[][] fields;
    private int cursor;
    private int sampleCount;
    private long completedTicks;
    private long lastLoggedTick;
    private String policyLabel = "JAVA";

    SchedulerMetrics(int windowTicks) {
        if (windowTicks <= 0) throw new IllegalArgumentException("Non-positive scheduler metrics window");
        this.windowTicks = windowTicks;
        fields = new long[FIELD_COUNT][windowTicks];
    }

    /** Mirrors PM's actual tick metadata hook; it does not reset the window or cadence. */
    void beginTick(String policyLabel) {
        this.policyLabel = Objects.requireNonNull(policyLabel);
    }

    /** One sample for each entered actual tick, including the tick that reports a failure. */
    void record(Sample sample) {
        Objects.requireNonNull(sample);
        long nextTick = Math.addExact(completedTicks, 1);
        fields[0][cursor] = sample.totalWallNanos;
        fields[1][cursor] = sample.mainWallNanos;
        fields[2][cursor] = sample.concurrentStageWallNanos;
        fields[3][cursor] = sample.joinWallNanos;
        fields[4][cursor] = sample.plannedWallNanos;
        fields[5][cursor] = sample.cleanupWallNanos;
        fields[6][cursor] = sample.structureWorkerWallNanos;
        fields[7][cursor] = sample.mainTasks;
        fields[8][cursor] = sample.concurrentTasks;
        fields[9][cursor] = sample.customMainTasks;
        fields[10][cursor] = sample.customConcurrentTasks;
        fields[11][cursor] = sample.affinityGroups;
        cursor++;
        if (cursor == windowTicks) cursor = 0;
        if (sampleCount < windowTicks) sampleCount++;
        completedTicks = nextTick;
    }

    /** Reallocation resets samples, while completed actual tick identity survives. */
    void resizeIfNeeded(int newWindowTicks) {
        if (newWindowTicks <= 0) throw new IllegalArgumentException("Non-positive scheduler metrics window");
        if (newWindowTicks == windowTicks) return;
        fields = new long[FIELD_COUNT][newWindowTicks];
        windowTicks = newWindowTicks;
        cursor = 0;
        sampleCount = 0;
    }

    SchedulerReport snapshotReport(String policyLabel) {
        return new SchedulerReport(policyLabel, completedTicks, windowTicks, sampleCount,
            stats(0), stats(1), stats(2), stats(3), stats(4), stats(5), stats(6),
            stats(7), stats(8), stats(9), stats(10), stats(11));
    }

    /** PM's metrics flag controls periodic logging only. Reports continue collecting while off. */
    void maybeLog(int intervalTicks, boolean enabled, Consumer<SchedulerReport> log) {
        if (intervalTicks <= 0) throw new IllegalArgumentException("Non-positive scheduler log interval");
        if (!enabled || sampleCount == 0 || completedTicks == 0 || completedTicks == lastLoggedTick
            || completedTicks % intervalTicks != 0) return;
        lastLoggedTick = completedTicks;
        log.accept(snapshotReport(policyLabel));
    }

    private SchedulerReport.Stat stats(int field) {
        if (sampleCount == 0) return EMPTY;
        var sorted = Arrays.copyOf(fields[field], sampleCount);
        Arrays.sort(sorted);
        long quotient = 0, remainder = 0;
        for (long value : sorted) {
            quotient += value / sampleCount;
            remainder += value % sampleCount;
        }
        // Both sums are bounded: quotients <= Long.MAX_VALUE; remainders < int-sized n*n.
        long avg = quotient + remainder / sampleCount;
        int p95 = (int) ((long) sampleCount * 95 / 100);
        return new SchedulerReport.Stat(avg, sorted[p95], sorted[sampleCount - 1]);
    }

    record Sample(long totalWallNanos, long mainWallNanos, long concurrentStageWallNanos,
                  long joinWallNanos, long plannedWallNanos, long cleanupWallNanos,
                  long structureWorkerWallNanos, long mainTasks, long concurrentTasks,
                  long customMainTasks, long customConcurrentTasks, long affinityGroups) {
        Sample {
            if (totalWallNanos < 0 || mainWallNanos < 0 || concurrentStageWallNanos < 0 || joinWallNanos < 0
                || plannedWallNanos < 0 || cleanupWallNanos < 0 || structureWorkerWallNanos < 0
                || mainTasks < 0 || concurrentTasks < 0 || customMainTasks < 0 || customConcurrentTasks < 0 || affinityGroups < 0)
                throw new IllegalArgumentException("Negative scheduler metric sample");
        }
    }
}
