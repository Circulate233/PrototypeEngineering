package com.circulation.prototype_eng;

import ae2.api.behaviors.GenericSlotCapacities;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Initial values for newly placed hatches; existing hatches retain their saved capacities. */
public final class HatchConfig {
    public static int slotsPerSide = 9;
    public static long itemsPerSlot = 4096;
    public static long fluidPerSlot = 16000;
    public static long energyCapacity = 1000000;

    private HatchConfig() {
    }

    public static void load(File directory) {
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/hatches.cfg"));
        config.load();
        slotsPerSide = Integer.parseInt(config.getString("slotsPerSide", "hatches", "9",
                "Slots per input/output side for newly placed hatches (1-18). Restart required."));
        if (slotsPerSide < 1 || slotsPerSide > 18) {
            throw new IllegalArgumentException("slotsPerSide must be between 1 and 18");
        }
        itemsPerSlot = positiveLong(config, "itemsPerSlot", 4096);
        fluidPerSlot = positiveLong(config, "fluidPerSlot", 16000);
        energyCapacity = positiveLong(config, "energyCapacity", 1000000);
        if (config.hasChanged()) {
            config.save();
        }
    }

    private static long positiveLong(Configuration config, String name, long fallback) {
        long value = Long.parseLong(config.getString(name, "hatches", Long.toString(fallback),
                "Positive long capacity for newly placed hatches, in native units. Restart required."));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static Reference2LongMap<AEKeyType> resourceCapacities() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        for (AEKeyType type : AEKeyTypes.getAll()) {
            long registered = GenericSlotCapacities.getMap().getLong(type);
            capacities.put(type, registered > 0 ? registered : Math.multiplyExact(Math.max(1L, type.getAmountPerUnit()), 64));
        }
        capacities.put(AEKeyType.items(), itemsPerSlot);
        capacities.put(AEKeyType.fluids(), fluidPerSlot);
        return capacities;
    }
}
