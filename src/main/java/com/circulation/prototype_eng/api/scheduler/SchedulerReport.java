package com.circulation.prototype_eng.api.scheduler;


/**
 * Immutable on-demand owner report. Wall-time windows can overlap; none is worker CPU time.
 */
public record SchedulerReport(String policyLabel, long completedTicks, int windowTicks, int sampleCount,
                              Stat totalWallNanos, Stat mainWallNanos, Stat concurrentStageWallNanos,
                              Stat joinWallNanos, Stat plannedWallNanos, Stat cleanupWallNanos,
                              Stat structureWorkerWallNanos, Stat mainTasks, Stat concurrentTasks,
                              Stat customMainTasks, Stat customConcurrentTasks, Stat affinityGroups) {
    /**
     * PM's actual periodic report, with explicit wall scope and no dispatch/CPU equivalence.
     */
    public String toLogLine() {
        return "[PE Scheduler] policy=" + policyLabel + " window=" + sampleCount + "/" + windowTicks
            + "t ticks=" + completedTicks + " wall(avg/p95/max) total=" + totalWallNanos.wallMicros() + "µs"
            + " main=" + mainWallNanos.wallMicros() + "µs concurrentStage=" + concurrentStageWallNanos.wallMicros() + "µs"
            + " join=" + joinWallNanos.wallMicros() + "µs planned=" + plannedWallNanos.wallMicros() + "µs"
            + " cleanup=" + cleanupWallNanos.wallMicros() + "µs structureWorker=" + structureWorkerWallNanos.wallMicros() + "µs"
            + " tasks(main/concurrent/customM/customC)=" + mainTasks.counts() + "/" + concurrentTasks.counts()
            + "/" + customMainTasks.counts() + "/" + customConcurrentTasks.counts() + " groups=" + affinityGroups.counts();
    }

    /**
     * Nonnegative samples summarized with PM's sorted-index p95 selection.
     */
    public record Stat(long avg, long p95, long max) {
        private static String micros(long nanos) {
            long fraction = nanos % 1000;
            return nanos / 1000 + "." + (fraction < 10 ? "00" : fraction < 100 ? "0" : "") + fraction;
        }

        public String wallMicros() {
            return micros(avg) + "/" + micros(p95) + "/" + micros(max);
        }

        public String counts() {
            return avg + "/" + p95 + "/" + max;
        }
    }
}
