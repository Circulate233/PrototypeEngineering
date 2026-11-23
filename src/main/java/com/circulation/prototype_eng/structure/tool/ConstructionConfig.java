package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class ConstructionConfig {
    private static final int DEFAULT_BUDGET_MILLIS = 10;
    private static final String CATEGORY = "construction";
    private static final String KEY = "tickBudgetMillis";
    private static File configurationFile;
    private static int budgetMillis = DEFAULT_BUDGET_MILLIS;

    private ConstructionConfig() {
    }

    public static void load(File directory) {
        configurationFile = new File(directory, Tags.MOD_ID + "/builder.cfg");
        try {
            reload();
        } catch (IOException | RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Cannot load construction configuration {}", configurationFile, failure);
            throw new IllegalStateException("Cannot load construction configuration " + configurationFile, failure);
        }
    }

    static int reload() throws IOException {
        var config = read();
        var budget = property(config);
        int requested = budget.getInt(0);
        if (requested < 1) throw new IllegalArgumentException(CATEGORY + "." + KEY + " must be a positive integer: " + budget.getString());
        return apply(config, requested);
    }

    static int setBudgetMillis(int requested) throws IOException {
        var config = read();
        var budget = property(config);
        if (budget.getInt(0) != requested) budget.set(requested);
        return apply(config, requested);
    }

    private static int apply(Configuration config, int requested) throws IOException {
        var construction = config.getCategory(CATEGORY);
        if (construction.containsKey("blocksPerBatch")) construction.remove("blocksPerBatch");
        if (construction.containsKey("workIntervalTicks")) construction.remove("workIntervalTicks");
        if (config.hasChanged()) {
            config.save();
            // Forge logs write failures without throwing, so read back before updating the live budget.
            var persisted = read();
            if (!persisted.hasKey(CATEGORY, KEY) || persisted.getCategory(CATEGORY).get(KEY).getInt(0) != requested
                || persisted.hasKey(CATEGORY, "blocksPerBatch") || persisted.hasKey(CATEGORY, "workIntervalTicks")) {
                throw new IOException("Could not save " + configurationFile);
            }
        }
        budgetMillis = requested;
        return requested;
    }

    private static Configuration read() throws IOException {
        if (configurationFile == null) throw new IllegalStateException("Construction configuration has not been loaded");
        var config = new Configuration(configurationFile);
        if (!configurationFile.isFile() || !configurationFile.canRead()) throw new IOException("Could not read " + configurationFile);
        return config;
    }

    private static Property property(Configuration config) {
        return config.get(CATEGORY, KEY, Integer.toString(DEFAULT_BUDGET_MILLIS),
            "Shared server budget in milliseconds per tick for construction and dismantling. Reload with /pe builder reload.",
            Property.Type.INTEGER);
    }

    static long tickBudgetNanos() {
        return TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    }
}
