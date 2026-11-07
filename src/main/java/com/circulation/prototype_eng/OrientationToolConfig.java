package com.circulation.prototype_eng;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** PM's existing orientation-tool defaults and bounds; loaded before item registration. */
public final class OrientationToolConfig {
    private static final Settings DEFAULTS = new Settings(1024, 14, 20.0, 5.0, -2.4, 5, 5, 1, 5, 1);
    private static Settings settings = DEFAULTS;

    private OrientationToolConfig() { }

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/tools.cfg"));
        config.load();
        String category = "tools";
        settings = new Settings(
            config.getInt("orientationToolMaxDurability", category, DEFAULTS.maxDurability(), 1, 100000, "Max durability of the controller orientation tool."),
            config.getInt("orientationToolEnchantability", category, DEFAULTS.enchantability(), 0, 100, "Enchantability of the controller orientation tool."),
            config.getFloat("orientationToolAttackDamageEnabled", category, (float) DEFAULTS.attackDamageEnabled(), 0, 2048, "Attack damage modifier when enabled."),
            config.getFloat("orientationToolAttackDamageDisabled", category, (float) DEFAULTS.attackDamageDisabled(), 0, 2048, "Attack damage modifier when disabled."),
            config.getFloat("orientationToolAttackSpeed", category, (float) DEFAULTS.attackSpeed(), -8, 8, "Attack speed modifier of the tool."),
            config.getInt("orientationToolDurabilityCostRotateEnabled", category, DEFAULTS.durabilityCostRotateEnabled(), 0, 1000, "Durability cost per controller rotation."),
            config.getInt("orientationToolDurabilityCostAttackEnabled", category, DEFAULTS.durabilityCostAttackEnabled(), 0, 1000, "Durability cost per enabled attack."),
            config.getInt("orientationToolDurabilityCostAttackDisabled", category, DEFAULTS.durabilityCostAttackDisabled(), 0, 1000, "Durability cost per disabled attack."),
            config.getInt("orientationToolDurabilityCostBreakEnabled", category, DEFAULTS.durabilityCostBreakEnabled(), 0, 1000, "Durability cost per enabled block break."),
            config.getInt("orientationToolDurabilityCostBreakDisabled", category, DEFAULTS.durabilityCostBreakDisabled(), 0, 1000, "Durability cost per disabled block break."));
        if (config.hasChanged()) { config.save(); }
    }

    public static Settings settings() { return settings; }

    public record Settings(int maxDurability, int enchantability, double attackDamageEnabled,
            double attackDamageDisabled, double attackSpeed, int durabilityCostRotateEnabled,
            int durabilityCostAttackEnabled, int durabilityCostAttackDisabled,
            int durabilityCostBreakEnabled, int durabilityCostBreakDisabled) { }
}
