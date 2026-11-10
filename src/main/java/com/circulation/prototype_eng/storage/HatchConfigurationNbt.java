package com.circulation.prototype_eng.storage;

import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Effective profiles are restored before attempting the current global target. */
public final class HatchConfigurationNbt {
    private HatchConfigurationNbt() {
    }

    public static NBTTagCompound resource(ResourceHatchConfiguration profile) {
        var tag = new NBTTagCompound();
        if (profile.input() != null) tag.setTag("Input", resourceSide(profile.input()));
        if (profile.output() != null) tag.setTag("Output", resourceSide(profile.output()));
        return tag;
    }

    private static NBTTagCompound resourceSide(ResourceHatchConfiguration.Side side) {
        var tag = new NBTTagCompound();
        tag.setInteger("Slots", side.slots());
        var capacities = new NBTTagCompound();
        side.capacities().forEach((type, capacity) -> capacities.setLong(type.getId().toString(), capacity));
        tag.setTag("Capacities", capacities);
        if (side.basis() != null) {
            tag.setLong("ItemEquivalent", side.basis().itemEquivalent());
            var ratios = new NBTTagCompound();
            side.basis().ratios().forEach((type, ratio) -> ratios.setLong(type.getId().toString(), ratio));
            tag.setTag("CapacityRatios", ratios);
        }
        return tag;
    }

    public static ResourceHatchConfiguration resource(NBTTagCompound tag, HatchMode mode) {
        requireSides(tag, mode);
        return new ResourceHatchConfiguration(mode.hasInput() ? resourceSide(StrictNbt.requireCompound(tag, "Input")) : null,
            mode.hasOutput() ? resourceSide(StrictNbt.requireCompound(tag, "Output")) : null);
    }

    public static ResourceHatchConfiguration.Side resourceSide(NBTTagCompound tag) {
        var limits = StrictNbt.requireCompound(tag, "Capacities");
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        for (var id : limits.getKeySet()) {
            var type = AEKeyTypes.get(new ResourceLocation(id));
            if (type == null) throw new IllegalArgumentException("Unknown saved resource type " + id);
            capacities.put(type, StrictNbt.requireLong(limits, id));
        }
        if (tag.hasKey("ItemEquivalent") || tag.hasKey("CapacityRatios")) {
            var ratios = new Reference2LongArrayMap<AEKeyType>();
            var savedRatios = StrictNbt.requireCompound(tag, "CapacityRatios");
            for (var id : savedRatios.getKeySet()) {
                var type = AEKeyTypes.get(new ResourceLocation(id));
                if (type == null) throw new IllegalArgumentException("Unknown saved resource ratio type " + id);
                ratios.put(type, StrictNbt.requireLong(savedRatios, id));
            }
            return new ResourceHatchConfiguration.Side(StrictNbt.requireInt(tag, "Slots"), capacities,
                new ResourceHatchConfiguration.CapacityBasis(StrictNbt.requireLong(tag, "ItemEquivalent"), ratios));
        }
        return new ResourceHatchConfiguration.Side(StrictNbt.requireInt(tag, "Slots"), capacities);
    }

    public static NBTTagCompound energy(EnergyHatchConfiguration profile) {
        var tag = new NBTTagCompound();
        if (profile.input() != null) tag.setTag("Input", energySide(profile.input()));
        if (profile.output() != null) tag.setTag("Output", energySide(profile.output()));
        return tag;
    }

    private static NBTTagCompound energySide(EnergyHatchConfiguration.Side side) {
        var tag = new NBTTagCompound();
        tag.setLong("Capacity", side.capacity());
        tag.setLong("Transfer", side.transfer());
        return tag;
    }

    public static EnergyHatchConfiguration energy(NBTTagCompound tag, HatchMode mode) {
        requireSides(tag, mode);
        return new EnergyHatchConfiguration(energySide(tag, "Input", mode.hasInput()), energySide(tag, "Output", mode.hasOutput()));
    }

    private static @Nullable EnergyHatchConfiguration.Side energySide(NBTTagCompound tag, String name, boolean present) {
        if (!present) return null;
        var side = StrictNbt.requireCompound(tag, name);
        return new EnergyHatchConfiguration.Side(StrictNbt.requireLong(side, "Capacity"), StrictNbt.requireLong(side, "Transfer"));
    }

    private static void requireSides(NBTTagCompound tag, HatchMode mode) {
        StrictNbt.requireKeys(tag, "Input", "Output");
        if (tag.hasKey("Input") != mode.hasInput() || tag.hasKey("Output") != mode.hasOutput())
            throw new IllegalArgumentException("Saved profile differs from physical hatch direction");
    }

    public static long nonnegative(NBTTagCompound tag, String name) {
        long amount = tag.hasKey(name) ? StrictNbt.requireLong(tag, name) : 0;
        if (amount < 0) throw new IllegalArgumentException("Negative saved hatch value " + name);
        return amount;
    }

    public static long energyRate(NBTTagCompound tag, String name) {
        long rate = tag.hasKey(name) ? StrictNbt.requireLong(tag, name) : -1;
        if (rate < -1) throw new IllegalArgumentException("Invalid saved hatch rate " + name);
        return rate;
    }
}
