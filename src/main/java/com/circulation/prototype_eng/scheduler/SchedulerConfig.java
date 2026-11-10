package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.Tags;
import net.minecraftforge.common.config.Configuration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Server defaults and explicit reloads; running sessions apply requests at a joined tick boundary. */
public final class SchedulerConfig {
    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static Settings settings = new Settings(Math.max(2, PROCESSORS), Math.clamp(PROCESSORS, 2, 8));
    private static File configurationFile;

    private SchedulerConfig() {
    }

    public static void load(File directory) {
        configurationFile = new File(Objects.requireNonNull(directory), Tags.MOD_ID + "/scheduler.cfg");
        reload();
    }

    public static Settings reload() {
        if (configurationFile == null) throw new IllegalStateException("Scheduler configuration has not been loaded");
        var config = new Configuration(configurationFile);
        config.load();
        settings = new Settings(
            config.getInt("workerThreads", "scheduler", Math.max(2, PROCESSORS), 1, Math.max(2, PROCESSORS) * 2,
                "Worker count; reload applies at the next safe server tick boundary."),
            config.getInt("laneCount", "scheduler", Math.clamp(PROCESSORS, 2, 8), 1, 32,
                "Serialized affinity lane count; reload preserves registered and queued work."),
            Duration.ofMillis(config.getInt("plannedTaskTimeoutMillis", "scheduler", 5000, 1, 60000,
                "Future planned capture/cleanup budget; an existing forward deadline never renews.")),
            config.getBoolean("metricsEnabled", "scheduler", false,
                "Enable periodic statistics logging. Recording and reports remain available when false."),
            config.getInt("metricsLogIntervalTicks", "scheduler", 200, 1, 1200,
                "Periodic log interval in completed scheduler ticks."),
            config.getInt("metricsWindowTicks", "scheduler", 400, 20, 6000,
                "Bounded sample window; changing its size resets samples but preserves log cadence."),
            configuredWorkerStrategy(config));
        if (config.hasChanged()) config.save();
        return settings;
    }

    public static Settings settings() {
        return settings;
    }

    private static WorkerStrategy configuredWorkerStrategy(Configuration config) {
        var name = config.getString("workerStrategy", "scheduler", "PLATFORM_POOL",
            "PLATFORM_POOL bounds CPU workers; VIRTUAL_PER_TASK creates one virtual thread per task and ignores workerThreads. Lanes remain serialized.",
            new String[]{"PLATFORM_POOL", "VIRTUAL_PER_TASK"});
        var strategy = WorkerStrategy.fromName(name);
        if (strategy != null) return strategy;
        config.get("scheduler", "workerStrategy", "PLATFORM_POOL").set("PLATFORM_POOL");
        return WorkerStrategy.PLATFORM_POOL;
    }

    /** Actual executor creation choices inside the original scheduler, with identical ownership rules. */
    public enum WorkerStrategy {
        PLATFORM_POOL, VIRTUAL_PER_TASK;

        @Nullable
        public static WorkerStrategy fromName(String name) {
            return switch (name.toUpperCase(Locale.ROOT)) {
                case "PLATFORM_POOL" -> PLATFORM_POOL;
                case "VIRTUAL_PER_TASK" -> VIRTUAL_PER_TASK;
                default -> null;
            };
        }

        public String policyLabel() {
            return this == PLATFORM_POOL ? "JAVA" : "JAVA/VIRTUAL_PER_TASK";
        }
    }

    public record Settings(int workerThreads, int laneCount, Duration plannedTimeout, boolean metricsEnabled,
                           int metricsLogIntervalTicks, int metricsWindowTicks, WorkerStrategy workerStrategy) {
        public Settings(int workerThreads, int laneCount) {
            this(workerThreads, laneCount, Duration.ofSeconds(5), false, 200, 400);
        }

        public Settings(int workerThreads, int laneCount, Duration plannedTimeout, boolean metricsEnabled,
                        int metricsLogIntervalTicks, int metricsWindowTicks) {
            this(workerThreads, laneCount, plannedTimeout, metricsEnabled, metricsLogIntervalTicks, metricsWindowTicks,
                WorkerStrategy.PLATFORM_POOL);
        }

        public Settings {
            if (workerThreads < 1 || laneCount < 1)
                throw new IllegalArgumentException("Scheduler thread counts must be positive");
            Objects.requireNonNull(plannedTimeout);
            Objects.requireNonNull(workerStrategy);
            if (plannedTimeout.isNegative() || plannedTimeout.isZero())
                throw new IllegalArgumentException("Planned budget must be positive");
            plannedTimeout.toNanos(); // Validate representability before accepting a runtime request.
            if (metricsLogIntervalTicks < 1 || metricsWindowTicks < 1)
                throw new IllegalArgumentException("Metrics tick counts must be positive");
        }

        @SuppressWarnings("unused") // Public millisecond display convenience; exact runtime authority uses Duration.
        public long plannedTaskTimeoutMillis() {
            return plannedTimeout.toMillis();
        }

        public Settings withMetricsEnabled(boolean enabled) {
            return new Settings(workerThreads, laneCount, plannedTimeout, enabled, metricsLogIntervalTicks, metricsWindowTicks,
                workerStrategy);
        }

        public Settings withWorkerStrategy(WorkerStrategy strategy) {
            return new Settings(workerThreads, laneCount, plannedTimeout, metricsEnabled, metricsLogIntervalTicks,
                metricsWindowTicks, strategy);
        }
    }
}
