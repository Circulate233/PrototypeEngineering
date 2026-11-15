package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.SchedulerReport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerMetricsTest {
    private static void record(SchedulerMetrics metrics, long total, long tasks) {
        metrics.record(total, total / 2, total / 3, total / 4,
            total / 5, total / 6, total / 7, tasks, tasks, tasks, tasks, tasks);
    }

    @Test
    void partialAndWrappedWindowUsesLatestSamplesAndPreservesCompletedTickIdentity() {
        var metrics = new SchedulerMetrics(3);
        assertEquals(0, metrics.snapshotReport("JAVA").sampleCount());
        record(metrics, 10, 1);
        record(metrics, 30, 3);
        var partial = metrics.snapshotReport("JAVA");
        assertEquals(new SchedulerReport.Stat(20, 30, 30), partial.totalWallNanos());
        assertEquals(2, partial.sampleCount());
        record(metrics, 50, 5);
        record(metrics, 90, 9);
        var wrapped = metrics.snapshotReport("JAVA");
        assertEquals(new SchedulerReport.Stat(56, 90, 90), wrapped.totalWallNanos());
        assertEquals(new SchedulerReport.Stat(5, 9, 9), wrapped.mainTasks());
        assertEquals(4, wrapped.completedTicks());
        assertEquals(3, wrapped.sampleCount());
        assertEquals(new SchedulerReport.Stat(20, 30, 30), partial.totalWallNanos());
    }

    @Test
    void loggingOffRetainsReportsAndResizeDoesNotResetCadenceOrEmitTwice() {
        var metrics = new SchedulerMetrics(3);
        var logs = new ObjectArrayList<SchedulerReport>();
        metrics.beginTick("JAVA");
        metrics.maybeLog(2, true, logs::add);
        assertTrue(logs.isEmpty());
        record(metrics, 10, 1);
        record(metrics, 20, 2);
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
        record(metrics, 30, 3);
        metrics.maybeLog(2, true, logs::add);
        assertEquals(1, logs.size());
        record(metrics, 40, 4);
        metrics.maybeLog(2, true, logs::add);
        assertEquals(2, logs.size());
        assertEquals(4, logs.getLast().completedTicks());
        assertEquals(1, logs.getLast().sampleCount());
        assertEquals(new SchedulerReport.Stat(40, 40, 40), logs.getLast().totalWallNanos());
    }
}
