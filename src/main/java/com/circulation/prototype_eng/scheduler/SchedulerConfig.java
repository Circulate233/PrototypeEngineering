package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.Tags;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** PM's ordinary worker/lane defaults, read before a server session creates its owned executors. */
public final class SchedulerConfig {
    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static Settings settings = new Settings(Math.max(2, PROCESSORS), Math.clamp(PROCESSORS, 2, 8));

    private SchedulerConfig() { }

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/scheduler.cfg"));
        config.load();
        settings = new Settings(
            config.getInt("workerThreads", "scheduler", Math.max(2, PROCESSORS), 1, Integer.MAX_VALUE,
                "Ordinary worker count; applied when a server starts."),
            config.getInt("laneCount", "scheduler", Math.clamp(PROCESSORS, 2, 8), 1, Integer.MAX_VALUE,
                "Fixed affinity lane count; applied when a server starts."));
        if (config.hasChanged()) { config.save(); }
    }

    public static Settings settings() { return settings; }

    public record Settings(int workerThreads, int laneCount) {
        public Settings {
            if (workerThreads < 1 || laneCount < 1) { throw new IllegalArgumentException("Scheduler thread counts must be positive"); }
        }
    }
}
