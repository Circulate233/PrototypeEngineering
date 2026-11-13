package com.circulation.prototype_eng;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Server-side construction work limits, independent of a player's tool settings.
 */
public final class BuilderConfig {
    private static int interval = 1;
    private static int batch = 8;

    private BuilderConfig() {
    }

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/builder.cfg"));
        config.load();
        interval = config.getInt("workIntervalTicks", "construction", 1, 1, 1200,
            "Ticks between construction/dismantling batches. 20 ticks is one second.");
        batch = config.getInt("blocksPerBatch", "construction", 8, 1, 4096,
            "Maximum unfinished positions attempted per batch.");
        if (config.hasChanged()) config.save();
    }

    public static int interval() {
        return interval;
    }

    public static int batch() {
        return batch;
    }
}
