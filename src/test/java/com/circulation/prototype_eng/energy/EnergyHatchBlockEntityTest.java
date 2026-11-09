package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jetbrains.annotations.NotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class EnergyHatchBlockEntityTest {
    @Test
    void mixedDirectionsKeepMachineRequirementsAndExternalTransfersInSeparateBalances() {
        var tile = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        var input = Objects.requireNonNull(tile.inputStorage());
        var output = Objects.requireNonNull(tile.outputStorage());
        var forge = new ForgeEnergyPort(input, output);
        assertNotSame(input.storageIdentity(), output.storageIdentity());
        assertEquals(30, forge.receiveEnergy(30, false));
        assertEquals(0, forge.extractEnergy(40, false));
        assertEquals(15, output.receiveEnergy(15, Actionable.MODULATE));
        assertEquals(7, forge.extractEnergy(7, false));
        assertEquals(30, input.storedEnergy());
        assertEquals(8, output.storedEnergy());

        var take = Objects.requireNonNull(input.reserveExtractEnergy(input.energySnapshot(), 20).reservation());
        var give = Objects.requireNonNull(output.reserveReceiveEnergy(output.energySnapshot(), 20).reservation());
        assertEquals(10, input.extractEnergy(40, Actionable.SIMULATE));
        assertEquals(8, forge.extractEnergy(40, true));
        assertTrue(ResourceTransactions.commit(List.of(take, give)));
        assertEquals(10, input.storedEnergy());
        assertEquals(28, forge.extractEnergy(100, false));
        assertEquals(10, input.storedEnergy());
    }

    @Test
    void bothMixedBalancesSurviveSaveReloadAndReattachmentWithoutRevivingEitherOldPort() {
        var tile = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        var input = Objects.requireNonNull(tile.inputStorage());
        var output = Objects.requireNonNull(tile.outputStorage());
        var oldPort = new ForgeEnergyPort(input, output);
        input.restore(41);
        output.restore(17);
        var tag = new NBTTagCompound();
        tile.saveAdditional(tag);
        assertFalse(tag.hasKey("Energy"));
        tile.loadTag(tag);
        assertEquals(0, oldPort.receiveEnergy(100, false));
        assertEquals(0, oldPort.extractEnergy(100, false));
        assertEquals(41, Objects.requireNonNull(tile.inputStorage()).storedEnergy());
        assertEquals(17, Objects.requireNonNull(tile.outputStorage()).storedEnergy());
        var loadedPort = new ForgeEnergyPort(tile.inputStorage(), tile.outputStorage());
        tile.onChunkUnload();
        tile.validate();
        assertEquals(0, loadedPort.receiveEnergy(100, false));
        assertEquals(0, loadedPort.extractEnergy(100, false));
        assertEquals(41, Objects.requireNonNull(tile.inputStorage()).storedEnergy());
        assertEquals(17, Objects.requireNonNull(tile.outputStorage()).storedEnergy());
        tile.setMaxInputRate(9);
        tile.setMaxOutputRate(4);
        assertEquals(9, Objects.requireNonNull(tile.inputStorage()).receiveEnergy(100, Actionable.MODULATE));
        assertEquals(4, Objects.requireNonNull(tile.outputStorage()).extractEnergy(100, Actionable.MODULATE));
        assertEquals(50, Objects.requireNonNull(tile.inputStorage()).storedEnergy());
        assertEquals(13, Objects.requireNonNull(tile.outputStorage()).storedEnergy());
    }

    @Test
    void legacyMixedEnergyMigratesOnceToInputAndNeverDuplicatesIntoOutput() {
        var tile = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        var legacy = new NBTTagCompound();
        legacy.setLong("Energy", 37);
        tile.loadTag(legacy);
        assertEquals(37, Objects.requireNonNull(tile.inputStorage()).storedEnergy());
        assertEquals(0, Objects.requireNonNull(tile.outputStorage()).storedEnergy());
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        var restored = new EnergyHatchBlockEntity();
        restored.loadTag(saved);
        assertEquals(37, Objects.requireNonNull(restored.inputStorage()).storedEnergy());
        assertEquals(0, Objects.requireNonNull(restored.outputStorage()).storedEnergy());
    }

    @Test
    void externalNbtHooksAndReattachmentRetainSettingsWithoutRevivingOldTransfers() {
        var tile = new DownstreamEnergyHatch();
        tile.setMaxInputRate(11);
        tile.storage().receiveEnergy(11, Actionable.MODULATE);
        tile.routeLimit = 4;
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        var old = tile.storage();
        tile.loadTag(saved);
        assertEquals(0, old.extractEnergy(100, Actionable.MODULATE));
        assertEquals(4, tile.storage().extractEnergy(100, Actionable.MODULATE));

        var unloaded = tile.storage();
        tile.onChunkUnload();
        tile.validate();
        assertNotSame(unloaded, tile.storage());
        assertEquals(0, unloaded.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(0, unloaded.extractEnergy(100, Actionable.MODULATE));
        assertEquals(11, tile.storage().receiveEnergy(100, Actionable.MODULATE));
        assertEquals(4, tile.storage().extractEnergy(100, Actionable.MODULATE));

        var invalidated = tile.storage();
        tile.invalidate();
        tile.validate();
        assertEquals(0, invalidated.extractEnergy(100, Actionable.MODULATE));
        assertEquals(4, tile.storage().extractEnergy(100, Actionable.MODULATE));
    }
    @Test
    void changingRatesUpdatesMachineTransfersAndZeroRestoresTheConfiguredDefault() {
        var tile = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        var balance = tile.storage();
        tile.setMaxInputRate(7);
        tile.setMaxOutputRate(3);
        assertEquals(7, balance.receiveEnergy(Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(7, balance.receiveEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(3, balance.extractEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        tile.setMaxInputRate(0);
        tile.setMaxOutputRate(Long.MAX_VALUE);
        long defaultLimit = HatchConfig.energyTransfer(HatchTier.LV1);
        assertEquals(defaultLimit, balance.receiveEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(defaultLimit, balance.extractEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(4, balance.storedEnergy());
        assertSame(balance, tile.storage());
    }

    @Test
    void reloadClampsToCurrentConfigAndOldViewsCannotTransferAfterReloadOrUnload() {
        var tile = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        var oldBalance = tile.storage();
        var oldPort = new ForgeEnergyPort(oldBalance, HatchMode.MIXED);
        oldBalance.receiveEnergy(15, Actionable.MODULATE);
        var tag = new NBTTagCompound();
        tag.setLong("Energy", Long.MAX_VALUE);
        tag.setLong("Capacity", Long.MAX_VALUE);
        tag.setLong("MaxInRate", 11);
        tag.setLong("MaxOutRate", 5);
        tile.readFromNBT(tag);
        var restored = tile.storage();
        assertEquals(0, oldPort.receiveEnergy(100, false));
        assertEquals(0, oldPort.extractEnergy(100, false));
        assertEquals(0, restored.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(5, restored.extractEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(HatchConfig.energyCapacity(HatchTier.LV1) - 5, restored.savedEnergy());
        assertEquals(5, restored.receiveEnergy(100, Actionable.MODULATE));

        tile.onChunkUnload();
        assertEquals(0, restored.extractEnergy(100, Actionable.MODULATE));
        assertEquals(0, restored.receiveEnergy(100, Actionable.MODULATE));
        // Loading while the owner is invalid must not expose a live balance.
        tile.invalidate();
        tag.setLong("Energy", 20);
        tile.readFromNBT(tag);
        assertEquals(0, tile.storage().extractEnergy(100, Actionable.MODULATE));
        assertEquals(20, tile.storage().savedEnergy());
    }

    private static final class DownstreamEnergyHatch extends EnergyHatchBlockEntity {
        private long routeLimit;

        private DownstreamEnergyHatch() {
            super(HatchMode.MIXED, HatchTier.LV1);
        }

        @Override
        public void saveAdditional(@NotNull NBTTagCompound tag) {
            super.saveAdditional(tag);
            tag.setLong("RouteLimit", routeLimit);
        }

        @Override
        public void loadTag(@NotNull NBTTagCompound tag) {
            super.loadTag(tag);
            routeLimit = tag.getLong("RouteLimit");
            setMaxOutputRate(routeLimit);
        }
    }
}
