package com.circulation.prototype_eng;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** File defaults for newly placed universal ME assemblies. */
public final class MeAssemblyConfig {
    private static volatile int inputSlots = 18;
    private static volatile int patternPages = 1;

    private MeAssemblyConfig() {}

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/me_assemblies.cfg"));
        config.load();
        int slots = Integer.parseInt(config.getString("inputSlots", "resources", "18", "Positive input marker/real slot count for ME input assemblies and interfaces."));
        int pages = Integer.parseInt(config.getString("pages", "patterns", "1", "Positive pattern page count; each page contains 36 patterns."));
        if (slots <= 0 || pages <= 0 || pages > Integer.MAX_VALUE / 36)
            throw new IllegalArgumentException("ME assembly slot/page counts must be positive and fit the inventory index");
        inputSlots = slots;
        patternPages = pages;
        if (config.hasChanged()) config.save();
    }

    public static int inputSlots() {
        return inputSlots;
    }

    public static int patternPages() {
        return patternPages;
    }
}
