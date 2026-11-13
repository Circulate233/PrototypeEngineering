package com.circulation.prototype_eng.storage;

import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

/**
 * Reads item display facts without constructing a tile, world or balance.
 */
public final class HatchItemProfiles {
    private HatchItemProfiles() {
    }

    public static ResourceHatchConfiguration resource(@Nullable NBTTagCompound saved, HatchTier tier, HatchMode mode) {
        return resource(saved, tier, mode, null);
    }

    public static ResourceHatchConfiguration resource(@Nullable NBTTagCompound saved, HatchTier tier, HatchMode mode,
                                                      @Nullable HatchConfigurationSnapshot authoritative) {
        if (saved == null)
            return authoritative == null ? HatchConfig.resourceConfiguration(tier, mode) : authoritative.resource(tier, mode);
        requireIdentity(saved, tier, mode);
        if (saved.hasKey("EffectiveProfile")) {
            try {
                return HatchConfigurationNbt.resource(StrictNbt.requireCompound(saved, "EffectiveProfile"), mode);
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Saved EffectiveProfile side slot sum overflows", invalid);
            }
        }
        if (saved.hasKey("Slots") || saved.hasKey("Capacities")) {
            try {
                var side = HatchConfigurationNbt.resourceSide(saved);
                return new ResourceHatchConfiguration(mode.hasInput() ? side : null, mode.hasOutput() ? side : null);
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Saved Slots sum overflows", invalid);
            }
        }
        return authoritative == null ? HatchConfig.resourceConfiguration(tier, mode) : authoritative.resource(tier, mode);
    }

    public static EnergyHatchConfiguration energy(@Nullable NBTTagCompound saved, HatchTier tier, HatchMode mode) {
        return energy(saved, tier, mode, null);
    }

    public static EnergyHatchConfiguration energy(@Nullable NBTTagCompound saved, HatchTier tier, HatchMode mode,
                                                  @Nullable HatchConfigurationSnapshot authoritative) {
        if (saved == null)
            return authoritative == null ? HatchConfig.energyConfiguration(tier, mode) : authoritative.energy(tier, mode);
        requireIdentity(saved, tier, mode);
        if (saved.hasKey("EffectiveProfile"))
            return HatchConfigurationNbt.energy(StrictNbt.requireCompound(saved, "EffectiveProfile"), mode);
        var defaults = authoritative == null ? HatchConfig.energyConfiguration(tier, mode) : authoritative.energy(tier, mode);
        if (mode == HatchMode.MIXED && saved.hasKey("InputEnergy") && saved.hasKey("Energy")
            || mode != HatchMode.MIXED && (saved.hasKey("InputEnergy") || saved.hasKey("OutputEnergy")))
            throw new IllegalArgumentException("Saved energy item has ambiguous directions");
        long primary = HatchConfigurationNbt.nonnegative(saved, mode == HatchMode.MIXED && saved.hasKey("InputEnergy") ? "InputEnergy" : "Energy");
        long input = mode.hasInput() ? primary : 0;
        long output = mode == HatchMode.MIXED ? HatchConfigurationNbt.nonnegative(saved, "OutputEnergy") : mode.hasOutput() ? primary : 0;
        var in = defaults.input();
        var out = defaults.output();
        return new EnergyHatchConfiguration(in == null ? null : new EnergyHatchConfiguration.Side(Math.max(in.capacity(), input), in.transfer()),
            out == null ? null : new EnergyHatchConfiguration.Side(Math.max(out.capacity(), output), out.transfer()));
    }

    /**
     * Physical automation directions use the corresponding saved side's ceiling and user setting.
     */
    public static EnergyRates energyRates(@Nullable NBTTagCompound saved, EnergyHatchConfiguration profile) {
        long input = energyRate(saved, "MaxInRate");
        long output = energyRate(saved, "MaxOutRate");
        return new EnergyRates(profile.input() == null ? 0 : profile.input().effectiveRate(input),
            profile.output() == null ? 0 : profile.output().effectiveRate(output));
    }

    private static long energyRate(@Nullable NBTTagCompound saved, String key) {
        if (saved == null) return -1;
        if (saved.hasKey("EffectiveProfile")) StrictNbt.requireLong(saved, key);
        return HatchConfigurationNbt.energyRate(saved, key);
    }

    private static void requireIdentity(NBTTagCompound saved, HatchTier tier, HatchMode mode) {
        boolean modern = saved.hasKey("EffectiveProfile");
        if ((modern || saved.hasKey("Tier")) && StrictNbt.requireInt(saved, "Tier") != tier.level()
            || (modern || saved.hasKey("Mode")) && !StrictNbt.requireString(saved, "Mode").equals(mode.name()))
            throw new IllegalArgumentException("Saved hatch profile differs from its physical item");
    }

    public record EnergyRates(long input, long output) {
    }
}
