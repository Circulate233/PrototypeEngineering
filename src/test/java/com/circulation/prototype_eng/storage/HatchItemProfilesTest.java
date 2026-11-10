package com.circulation.prototype_eng.storage;

import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HatchItemProfilesTest {
    private static NBTTagCompound identity(HatchMode mode) {
        var tag = new NBTTagCompound();
        tag.setString("Mode", mode.name());
        tag.setInteger("Tier", 1);
        return tag;
    }

    @Test
    void savedMixedResourceItemsDisplayTheirIndependentEffectiveProfilesInsteadOfNewDefaults() {
        var profile = new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(96, Map.of(AEKeyType.items(), 11L, AEKeyType.fluids(), 3210L)),
            new ResourceHatchConfiguration.Side(128, Map.of(AEKeyType.items(), 77L, AEKeyType.fluids(), 987_654L)));
        var tag = identity(HatchMode.MIXED);
        tag.setTag("EffectiveProfile", HatchConfigurationNbt.resource(profile));
        assertEquals(profile, HatchItemProfiles.resource(tag, HatchTier.LV1, HatchMode.MIXED));
        assertEquals(64_000L, Objects.requireNonNull(HatchItemProfiles.resource(null, HatchTier.LV1, HatchMode.MIXED).input()).capacities().get(AEKeyType.fluids()));
        tag.removeTag("Tier");
        assertThrows(IllegalArgumentException.class, () -> HatchItemProfiles.resource(tag, HatchTier.LV1, HatchMode.MIXED));
    }

    @Test
    void legacyResourceItemsKeepSavedSlotCountsAndCapacities() {
        var tag = identity(HatchMode.INPUT);
        tag.setInteger("Slots", 97);
        var capacities = new NBTTagCompound();
        capacities.setLong(AEKeyType.items().getId().toString(), 19);
        capacities.setLong(AEKeyType.fluids().getId().toString(), 7001);
        tag.setTag("Capacities", capacities);
        var profile = HatchItemProfiles.resource(tag, HatchTier.LV1, HatchMode.INPUT);
        assertEquals(97, Objects.requireNonNull(profile.input()).slots());
        assertEquals(7001L, profile.input().capacities().get(AEKeyType.fluids()));
        assertNull(profile.output());
    }

    @Test
    void energyItemsShowEffectiveSidesAndLegacyRestorationCapacitySeparatelyFromResourceRatios() {
        var profile = new EnergyHatchConfiguration(new EnergyHatchConfiguration.Side(9999, 123), new EnergyHatchConfiguration.Side(7777, 456));
        var saved = identity(HatchMode.MIXED);
        saved.setTag("EffectiveProfile", HatchConfigurationNbt.energy(profile));
        assertEquals(profile, HatchItemProfiles.energy(saved, HatchTier.LV1, HatchMode.MIXED));
        saved.setLong("MaxInRate", -1);
        saved.setLong("MaxOutRate", Long.MAX_VALUE);
        assertEquals(new HatchItemProfiles.EnergyRates(123, 456), HatchItemProfiles.energyRates(saved, profile));
        saved.setLong("MaxInRate", 17);
        saved.setLong("MaxOutRate", 29);
        assertEquals(new HatchItemProfiles.EnergyRates(17, 29), HatchItemProfiles.energyRates(saved, profile));
        saved.setLong("MaxInRate", 0);
        saved.setLong("MaxOutRate", 0);
        assertEquals(new HatchItemProfiles.EnergyRates(0, 0), HatchItemProfiles.energyRates(saved, profile));
        saved.setLong("MaxInRate", -2);
        assertThrows(IllegalArgumentException.class, () -> HatchItemProfiles.energyRates(saved, profile));
        saved.removeTag("MaxInRate");
        assertThrows(IllegalArgumentException.class, () -> HatchItemProfiles.energyRates(saved, profile));
        var inputOnly = new EnergyHatchConfiguration(profile.input(), null);
        var outputOnly = new EnergyHatchConfiguration(null, profile.output());
        assertEquals(new HatchItemProfiles.EnergyRates(123, 0), HatchItemProfiles.energyRates(null, inputOnly));
        assertEquals(new HatchItemProfiles.EnergyRates(0, 456), HatchItemProfiles.energyRates(null, outputOnly));
        var legacy = identity(HatchMode.MIXED);
        legacy.setLong("InputEnergy", 200_000);
        legacy.setLong("OutputEnergy", 300_000);
        var recovered = HatchItemProfiles.energy(legacy, HatchTier.LV1, HatchMode.MIXED);
        assertEquals(200_000, Objects.requireNonNull(recovered.input()).capacity());
        assertEquals(300_000, Objects.requireNonNull(recovered.output()).capacity());
        assertEquals(HatchConfig.energyCapacity(HatchTier.LV1), Objects.requireNonNull(HatchItemProfiles.energy(null, HatchTier.LV1, HatchMode.INPUT).input()).capacity());
    }
}
