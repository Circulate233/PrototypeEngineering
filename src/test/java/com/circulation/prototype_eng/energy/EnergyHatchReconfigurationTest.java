package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.storage.HatchConfigurationNbt;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyHatchReconfigurationTest {
    private static EnergyHatchConfiguration profile(long input, long output) {
        return new EnergyHatchConfiguration(new EnergyHatchConfiguration.Side(input, 31),
            new EnergyHatchConfiguration.Side(output, 47));
    }

    @Test
    void bothSidesMigrateUnderTheOriginalFenceAndReservedOrOverfullSidesRejectAtomically() {
        var old = new EnergyHatchStorage(profile(1000, 2000), 11, 7, 900, 1300, () -> {
        });
        var input = Objects.requireNonNull(old.input());
        var output = Objects.requireNonNull(old.output());
        var oldPort = new ForgeEnergyPort(input, output);
        var rejected = old.replace(profile(1000, 1200), 11, 7, () -> {
            }, () -> true,
            _ -> {
                throw new AssertionError("Overfull output must retain both sides");
            });
        assertEquals(HatchReconfigurationStatus.BLOCKED_CAPACITY, rejected.status());
        assertTrue(rejected.unchanged());
        assertArrayEquals(new long[]{900, 1300}, old.savedBalances());
        var reservation = Objects.requireNonNull(input.reserveExtractEnergy(input.energySnapshot(), 5).reservation());
        assertEquals(HatchReconfigurationStatus.PENDING_BUSY,
            old.replace(profile(2000, 3000), 11, 7, () -> {
                }, () -> true,
                _ -> {
                    throw new AssertionError("Active token must block replacement");
                }).status());
        ResourceTransactions.rollback(List.of(reservation));
        var installed = new EnergyHatchStorage[]{old};
        assertEquals(HatchReconfigurationStatus.APPLIED,
            old.replace(profile(2000, 3000), 11, 7, () -> {
                }, () -> true,
                candidate -> () -> installed[0] = candidate).status());
        assertArrayEquals(new long[]{900, 1300}, installed[0].savedBalances());
        assertNotSame(input.context(), Objects.requireNonNull(installed[0].input()).context());
        assertEquals(0, oldPort.receiveEnergy(100, false));
        assertEquals(0, oldPort.extractEnergy(100, false));
        assertEquals(11, Objects.requireNonNull(installed[0].input()).receiveEnergy(100, Actionable.MODULATE));
        assertEquals(7, Objects.requireNonNull(installed[0].output()).extractEnergy(100, Actionable.MODULATE));
    }

    @Test
    void rejectedPreparationReleasesBothOldContextsWithoutNotificationsOrBalanceLoss() {
        int[] notifications = {0};
        var old = new EnergyHatchStorage(profile(1000, 2000), -1, -1, 900, 1300, () -> {
        });
        assertThrows(IllegalArgumentException.class, () -> old.replace(profile(2000, 3000), -1, -1,
            () -> notifications[0]++, () -> true,
            _ -> {
                throw new IllegalArgumentException("Adapter preparation failed");
            }));
        assertArrayEquals(new long[]{900, 1300}, old.savedBalances());
        assertEquals(0, notifications[0]);
        assertEquals(31, old.primary().receiveEnergy(100, Actionable.MODULATE));
    }


    @Test
    void effectiveProfileAndBothBalancesSurviveNbtAndReattachmentWithSmallerPendingTarget() {
        var tag = new NBTTagCompound();
        tag.setString("Mode", "MIXED");
        tag.setInteger("Tier", 1);
        tag.setTag("EffectiveProfile", HatchConfigurationNbt.energy(profile(1000, 2000)));
        tag.setLong("InputEnergy", 900);
        tag.setLong("OutputEnergy", 1300);
        tag.setLong("MaxInRate", 11);
        tag.setLong("MaxOutRate", 7);
        tag.setBoolean("AutoInput", false);
        tag.setBoolean("AutoOutput", false);
        var tile = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        tile.loadTag(tag);
        var before = new ForgeEnergyPort(tile.inputStorage(), tile.outputStorage());
        tile.requestConfiguration(profile(100, 200));
        tile.invalidate();
        tile.validate();
        assertEquals(0, before.receiveEnergy(100, false));
        assertEquals(0, before.extractEnergy(100, false));
        assertEquals(900, Objects.requireNonNull(tile.inputStorage()).savedEnergy());
        assertEquals(1300, Objects.requireNonNull(tile.outputStorage()).savedEnergy());
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        var restored = new EnergyHatchBlockEntity();
        restored.loadTag(saved);
        assertEquals(900, Objects.requireNonNull(restored.inputStorage()).storedEnergy());
        assertEquals(1300, Objects.requireNonNull(restored.outputStorage()).storedEnergy());
        assertEquals(1000, Objects.requireNonNull(restored.inputStorage()).capacity());
        assertEquals(2000, Objects.requireNonNull(restored.outputStorage()).capacity());
        assertEquals(11, Objects.requireNonNull(restored.inputStorage()).receiveEnergy(100, Actionable.MODULATE));
        assertEquals(7, Objects.requireNonNull(restored.outputStorage()).extractEnergy(100, Actionable.MODULATE));
        var unchanged = restored.storage();
        saved.setLong("OutputEnergy", 2001);
        assertThrows(IllegalArgumentException.class, () -> restored.loadTag(saved));
        assertSame(unchanged, restored.storage());
        assertEquals(911, Objects.requireNonNull(restored.inputStorage()).storedEnergy());
        assertEquals(1293, Objects.requireNonNull(restored.outputStorage()).storedEnergy());
    }
}
