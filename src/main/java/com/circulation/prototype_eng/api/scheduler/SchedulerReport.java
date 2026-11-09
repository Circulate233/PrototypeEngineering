package com.circulation.prototype_eng.api.scheduler;

import java.util.Objects;
import java.util.Locale;

/** Immutable on-demand owner report. Wall-time windows can overlap; none is worker CPU time. */
public record SchedulerReport(String policyLabel, long completedTicks, int windowTicks, int sampleCount,
                              Stat totalWallNanos, Stat mainWallNanos, Stat concurrentStageWallNanos,
                              Stat joinWallNanos, Stat plannedWallNanos, Stat cleanupWallNanos,
                              Stat structureWorkerWallNanos, Stat mainTasks, Stat concurrentTasks,
                              Stat customMainTasks, Stat customConcurrentTasks, Stat affinityGroups) {
    public SchedulerReport {
        Objects.requireNonNull(policyLabel);
        if (completedTicks < 0 || windowTicks <= 0 || sampleCount < 0 || sampleCount > windowTicks)
            throw new IllegalArgumentException("Invalid scheduler report window");
        Objects.requireNonNull(totalWallNanos);
        Objects.requireNonNull(mainWallNanos);
        Objects.requireNonNull(concurrentStageWallNanos);
        Objects.requireNonNull(joinWallNanos);
        Objects.requireNonNull(plannedWallNanos);
        Objects.requireNonNull(cleanupWallNanos);
        Objects.requireNonNull(structureWorkerWallNanos);
        Objects.requireNonNull(mainTasks);
        Objects.requireNonNull(concurrentTasks);
        Objects.requireNonNull(customMainTasks);
        Objects.requireNonNull(customConcurrentTasks);
        Objects.requireNonNull(affinityGroups);
    }

    /** PM's actual periodic report, with explicit wall scope and no dispatch/CPU equivalence. */
    public String toLogLine() {
        return "[PE Scheduler] policy=" + policyLabel + " window=" + sampleCount + "/" + windowTicks
            + "t ticks=" + completedTicks + " wall(avg/p95/max) total=" + totalWallNanos.wallMicros() + "µs"
            + " main=" + mainWallNanos.wallMicros() + "µs concurrentStage=" + concurrentStageWallNanos.wallMicros() + "µs"
            + " join=" + joinWallNanos.wallMicros() + "µs planned=" + plannedWallNanos.wallMicros() + "µs"
            + " cleanup=" + cleanupWallNanos.wallMicros() + "µs structureWorker=" + structureWorkerWallNanos.wallMicros() + "µs"
            + " tasks(main/concurrent/customM/customC)=" + mainTasks.counts() + "/" + concurrentTasks.counts()
            + "/" + customMainTasks.counts() + "/" + customConcurrentTasks.counts() + " groups=" + affinityGroups.counts();
    }

    /** Nonnegative samples summarized with PM's sorted-index p95 selection. */
    public record Stat(long avg, long p95, long max) {
        public Stat {
            if (avg < 0 || p95 < 0 || max < avg || max < p95)
                throw new IllegalArgumentException("Invalid scheduler statistics");
        }

        public String wallMicros() {
            return micros(avg) + "/" + micros(p95) + "/" + micros(max);
        }

        public String counts() {
            return avg + "/" + p95 + "/" + max;
        }

        private static String micros(long nanos) {
            return nanos / 1000 + "." + String.format(Locale.ROOT, "%03d", nanos % 1000);
        }
    }
}
