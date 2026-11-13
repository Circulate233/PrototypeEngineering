package com.circulation.prototype_eng;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Server default for ordinary controllers without an explicit network requirement.
 */
public final class ControllerConfig {
    private static boolean defaultRequiresNetwork;

    private ControllerConfig() {
    }

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/controllers.cfg"));
        config.load();
        defaultRequiresNetwork = config.getBoolean("defaultRequiresNetwork", "network", false,
            "Require a logical team network for ordinary controllers unless their declaration overrides it.");
        if (config.hasChanged()) config.save();
    }

    public static boolean defaultRequiresNetwork() {
        return defaultRequiresNetwork;
    }
}
