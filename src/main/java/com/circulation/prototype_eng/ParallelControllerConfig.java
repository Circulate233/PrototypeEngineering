package com.circulation.prototype_eng;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * MMCE's five defaults; configuration is loaded before physical controller registration.
 */
public final class ParallelControllerConfig {
    public static final String[] TIERS = {"normal", "reinforced", "elite", "super", "ultimate"};
    private static final int[] DEFAULTS = {4, 16, 64, 256, 512};
    private static int[] maxima = DEFAULTS.clone();

    private ParallelControllerConfig() {
    }

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/parallel_controllers.cfg"));
        config.load();
        int[] values = new int[TIERS.length];
        for (int tier = 0; tier < values.length; tier++)
            values[tier] = config.getInt("maxParallelism", TIERS[tier], DEFAULTS[tier], 1, Integer.MAX_VALUE,
                "Maximum selected parallelism contributed by this controller.");
        maxima = values;
        if (config.hasChanged()) config.save();
    }

    public static int maximum(int tier) {
        return maxima[tier];
    }
}
