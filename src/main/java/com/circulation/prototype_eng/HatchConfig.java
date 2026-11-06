package com.circulation.prototype_eng;

import ae2.api.behaviors.GenericSlotCapacities;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Tier defaults migrated directly from PM's item/fluid/energy hatch configurations. */
public final class HatchConfig {
    private static final int[] SLOTS = new int[10];
    private static final int[] MIXED_SLOTS = new int[10];
    private static final long[] ITEMS = new long[10];
    private static final long[] FLUIDS = new long[10];
    private static final long[] ENERGY = new long[10];
    private static final long[] TRANSFER = new long[10];

    static {
        for (var tier : HatchTier.values()) {
            int i = tier.ordinal();
            SLOTS[i] = tier.slotsPerSide(HatchMode.INPUT);
            MIXED_SLOTS[i] = tier.slotsPerSide(HatchMode.MIXED);
            ITEMS[i] = tier.itemsPerSlot();
            FLUIDS[i] = tier.fluidPerSlot();
            ENERGY[i] = tier.energyCapacity();
            TRANSFER[i] = tier.energyTransfer();
        }
    }

    private HatchConfig() {}

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/hatches.cfg"));
        config.load();
        for (var tier : HatchTier.values()) {
            int i = tier.ordinal();
            String category = "tier" + tier.level();
            SLOTS[i] = config.getInt("slots", category, tier.slotsPerSide(HatchMode.INPUT), 1, 81, "Input/output resource slots.");
            MIXED_SLOTS[i] = config.getInt("mixedSlotsPerSide", category, tier.slotsPerSide(HatchMode.MIXED), 1, 81, "Independent slots on each mixed-hatch side.");
            ITEMS[i] = positiveLong(config, category, "itemsPerSlot", tier.itemsPerSlot());
            FLUIDS[i] = positiveLong(config, category, "fluidPerSlot", tier.fluidPerSlot());
            ENERGY[i] = positiveLong(config, category, "energyCapacity", tier.energyCapacity());
            TRANSFER[i] = positiveLong(config, category, "energyTransfer", tier.energyTransfer());
        }
        if (config.hasChanged()) { config.save(); }
    }

    private static long positiveLong(Configuration config, String category, String name, long fallback) {
        long value = Long.parseLong(config.getString(name, category, Long.toString(fallback), "Positive long value; restart required."));
        if (value <= 0) { throw new IllegalArgumentException(category + "." + name + " must be positive"); }
        return value;
    }

    public static int slotsPerSide(HatchTier tier, HatchMode mode) { return (mode == HatchMode.MIXED ? MIXED_SLOTS : SLOTS)[tier.ordinal()]; }
    public static long energyCapacity(HatchTier tier) { return ENERGY[tier.ordinal()]; }
    public static long energyTransfer(HatchTier tier) { return TRANSFER[tier.ordinal()]; }

    public static Reference2LongMap<AEKeyType> resourceCapacities(HatchTier tier) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        for (AEKeyType type : AEKeyTypes.getAll()) {
            long registered = GenericSlotCapacities.getMap().getLong(type);
            capacities.put(type, registered > 0 ? registered : Math.multiplyExact(Math.max(1L, type.getAmountPerUnit()), 64));
        }
        capacities.put(AEKeyType.items(), ITEMS[tier.ordinal()]);
        capacities.put(AEKeyType.fluids(), FLUIDS[tier.ordinal()]);
        return capacities;
    }
}
