package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.SchedulerReport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerMetricsTest {
    private static SchedulerMetrics.Sample sample(long total, long tasks) {
        return new SchedulerMetrics.Sample(total, total / 2, total / 3, total / 4,
            total / 5, total / 6, total / 7, tasks, tasks, tasks, tasks, tasks);
    }

    @Test
    void partialAndWrappedWindowUsesLatestSamplesAndPreservesCompletedTickIdentity() {
        var metrics = new SchedulerMetrics(3);
        assertEquals(0, metrics.snapshotReport("JAVA").sampleCount());
        metrics.record(sample(10, 1));
        metrics.record(sample(30, 3));
        var partial = metrics.snapshotReport("JAVA");
        assertEquals(new SchedulerReport.Stat(20, 30, 30), partial.totalWallNanos());
        assertEquals(2, partial.sampleCount());
        metrics.record(sample(50, 5));
        metrics.record(sample(90, 9));
        var wrapped = metrics.snapshotReport("JAVA");
        assertEquals(new SchedulerReport.Stat(56, 90, 90), wrapped.totalWallNanos());
        assertEquals(new SchedulerReport.Stat(5, 9, 9), wrapped.mainTasks());
        assertEquals(4, wrapped.completedTicks());
        assertEquals(3, wrapped.sampleCount());
        // Later writes cannot mutate the earlier immutable report.
        assertEquals(new SchedulerReport.Stat(20, 30, 30), partial.totalWallNanos());
    }

    @Test
    void percentileUsesActualPmIndexAndLargeSamplesDoNotOverflowAverage() {
        var metrics = new SchedulerMetrics(20);
        for (int value = 1; value <= 20; value++) metrics.record(sample(value, value));
        assertEquals(new SchedulerReport.Stat(10, 20, 20), metrics.snapshotReport("JAVA").totalWallNanos());
        var large = new SchedulerMetrics(2);
        large.record(sample(Long.MAX_VALUE, Long.MAX_VALUE));
        large.record(sample(Long.MAX_VALUE - 2, Long.MAX_VALUE - 2));
        assertEquals(new SchedulerReport.Stat(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE),
            large.snapshotReport("JAVA").totalWallNanos());
        assertEquals(new SchedulerReport.Stat(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE),
            large.snapshotReport("JAVA").mainTasks());
    }

    @Test
    void loggingOffRetainsReportsAndResizeDoesNotResetCadenceOrEmitTwice() {
        var metrics = new SchedulerMetrics(3);
        var logs = new ObjectArrayList<SchedulerReport>();
        metrics.beginTick("JAVA");
        metrics.maybeLog(2, true, logs::add);
        assertTrue(logs.isEmpty());
        metrics.record(sample(10, 1));
        metrics.record(sample(20, 2));
        metrics.maybeLog(2, false, logs::add);
        assertTrue(logs.isEmpty());
        assertEquals(2, metrics.snapshotReport("JAVA").sampleCount());
        metrics.maybeLog(2, true, logs::add);
        metrics.maybeLog(2, true, logs::add);
        assertEquals(1, logs.size());
        metrics.resizeIfNeeded(1);
        assertEquals(0, metrics.snapshotReport("JAVA").sampleCount());
        assertEquals(2, metrics.snapshotReport("JAVA").completedTicks());
        metrics.maybeLog(2, true, logs::add);
        assertEquals(1, logs.size());
        metrics.record(sample(30, 3));
        metrics.maybeLog(2, true, logs::add);
        assertEquals(1, logs.size());
        metrics.record(sample(40, 4));
        metrics.maybeLog(2, true, logs::add);
        assertEquals(2, logs.size());
        assertEquals(4, logs.getLast().completedTicks());
        assertEquals(1, logs.getLast().sampleCount());
        assertEquals(new SchedulerReport.Stat(40, 40, 40), logs.getLast().totalWallNanos());
    }
}
