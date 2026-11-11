package com.circulation.prototype_eng.storage;

import it.unimi.dsi.fastutil.objects.Reference2LongMaps;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTransferReservationTest {
    private static final KeyType TYPE = new KeyType("native_transfer");
    private static final Key KEY = new Key(TYPE, "resource");

    static {
        AEKeyTypes.register(TYPE);
    }

    @Test
    void resourceExportRefundAndImportActualRemainExactAfterReentrantSameKeyTransfers() {
        var output = new SlottedResourceBuffer(2, Reference2LongMaps.singleton(TYPE, 50L), () -> {});
        output.insert(KEY, 90, Actionable.MODULATE);
        output.context().lock();
        try {
            var transfer = Objects.requireNonNull(output.reserveTransfer(KEY, 80, false));
            assertEquals(10, output.amount(KEY));
            assertEquals(10, output.insert(KEY, 100, Actionable.MODULATE));
            assertEquals(20, output.extract(KEY, 100, Actionable.MODULATE));
            transfer.complete(30);
            assertEquals(50, output.amount(KEY));
            assertFalse(output.hasTransferReservation());
            assertTrue(output.idleForReplacement());
        } finally {
            output.context().unlock();
        }
        var input = new SlottedResourceBuffer(2, Reference2LongMaps.singleton(TYPE, 50L), () -> {});
        input.insert(KEY, 20, Actionable.MODULATE);
        input.context().lock();
        try {
            var transfer = Objects.requireNonNull(input.reserveTransfer(KEY, 60, true));
            assertEquals(20, input.amount(KEY));
            assertEquals(20, input.extract(KEY, 100, Actionable.MODULATE));
            assertEquals(40, input.insert(KEY, 100, Actionable.MODULATE));
            transfer.complete(25);
            assertEquals(65, input.amount(KEY));
            assertFalse(input.hasTransferReservation());
            assertTrue(input.idleForReplacement());
        } finally {
            input.context().unlock();
        }
    }

    @Test
    void energyPartialSettlementUsesReservedRightsAfterCallbackChangesBothLimitsToZero() {
        var input = new EnergyBuffer(100, 80, 80, () -> {});
        input.context().lock();
        try {
            var transfer = Objects.requireNonNull(input.reserveTransfer(60, true));
            assertEquals(0, input.storedEnergy());
            input.setTransferLimits(0, 0);
            transfer.complete(25);
            assertEquals(25, input.savedEnergy());
            assertFalse(input.hasTransferReservation());
            input.restore(25);
            assertTrue(input.context().idleForReplacement());
        } finally {
            input.context().unlock();
        }
        var output = new EnergyBuffer(100, 80, 80, () -> {});
        output.restore(80);
        output.context().lock();
        try {
            var transfer = Objects.requireNonNull(output.reserveTransfer(50, false));
            assertEquals(30, output.storedEnergy());
            assertEquals(10, output.receiveEnergy(10, Actionable.MODULATE));
            output.setTransferLimits(0, 0);
            transfer.complete(20);
            assertEquals(70, output.savedEnergy());
            assertFalse(output.hasTransferReservation());
            assertTrue(output.context().idleForReplacement());
        } finally {
            output.context().unlock();
        }
        var mixed = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        var mixedInput = Objects.requireNonNull(mixed.inputStorage());
        Objects.requireNonNull(mixed.outputStorage()).invalidate();
        long previousLimit = mixedInput.energySnapshot().maxReceive();
        assertThrows(IllegalStateException.class, () -> mixed.setMaxInputRate(0));
        assertEquals(-1, mixed.maxInputRate());
        assertEquals(previousLimit, mixedInput.energySnapshot().maxReceive());
    }

    @Test
    void slotScopedTransferRejectsInvalidResultReplayAndRecipeFenceWithoutLosingItsHold() {
        var buffer = new SlottedResourceBuffer(100_000, Reference2LongMaps.singleton(TYPE, 100L), () -> {});
        buffer.context().lock();
        try {
            var transfer = Objects.requireNonNull(buffer.reserveTransfer(99_999, KEY, 40, true));
            assertEquals(40, transfer.amount());
            assertThrows(IllegalArgumentException.class, () -> transfer.complete(41));
            assertThrows(IllegalArgumentException.class, () -> transfer.complete(-1));
            assertTrue(buffer.hasTransferReservation());
            transfer.complete(17);
            assertEquals(17, buffer.getAmount(99_999));
            assertEquals(0, buffer.getAmount(0));
            assertThrows(IllegalStateException.class, () -> transfer.complete(0));
            assertTrue(buffer.idleForReplacement());
        } finally {
            buffer.context().unlock();
        }
        var fence = StorageFence.tryAcquire(List.of(buffer.context()));
        assertNotNull(fence);
        try {
            fence.access(() -> assertThrows(IllegalStateException.class,
                () -> buffer.reserveTransfer(99_999, KEY, 1, true)));
        } finally {
            fence.release();
        }
    }

    @Test
    void uncertainNativeTransfersCannotBeRevivedBySameTileReloadOrReattachment() {
        var resourceTile = new ResourceHatchBlockEntity();
        resourceTile.loadTag(resourceTag());
        var currentResource = (SlottedResourceBuffer) Objects.requireNonNull(resourceTile.storage().input());
        currentResource.context().lock();
        try {
            assertNotNull(currentResource.reserveTransfer(0, KEY, 20, true));
            currentResource.context().invalidate();
        } finally {
            currentResource.context().unlock();
        }
        assertThrows(IllegalStateException.class, () -> resourceTile.loadTag(resourceTag()));
        resourceTile.invalidate();
        resourceTile.validate();
        assertSame(currentResource, resourceTile.storage().input());
        assertFalse(currentResource.isValid());
        var energyTile = new EnergyHatchBlockEntity(HatchMode.INPUT, HatchTier.LV1);
        var energy = energyTile.storage();
        energy.context().lock();
        try {
            assertNotNull(energy.reserveTransfer(20, true));
            energy.context().invalidate();
        } finally {
            energy.context().unlock();
        }
        assertThrows(IllegalStateException.class, () -> energyTile.loadTag(new NBTTagCompound()));
        energyTile.invalidate();
        energyTile.validate();
        assertSame(energy, energyTile.storage());
        assertFalse(energy.isValid());
    }

    private static NBTTagCompound resourceTag() {
        var tag = new NBTTagCompound();
        tag.setString("Mode", "INPUT");
        tag.setInteger("Tier", 1);
        tag.setInteger("Slots", 1);
        var capacities = new NBTTagCompound();
        capacities.setLong(TYPE.getId().toString(), 50);
        tag.setTag("Capacities", capacities);
        return tag;
    }
}
