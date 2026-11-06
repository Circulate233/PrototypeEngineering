package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.storage.HatchMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnergyBufferTest {
    @Test
    void forgeTransfersAndMachineConsumptionShareOneLongBalance() {
        int[] notifications = {0};
        var buffer = new EnergyBuffer(Long.MAX_VALUE, () -> notifications[0]++);
        var forge = new ForgeEnergyPort(buffer, HatchMode.INPUT);

        forge.receiveEnergy(Integer.MAX_VALUE, false);
        forge.receiveEnergy(Integer.MAX_VALUE, false);
        long received = 2L * Integer.MAX_VALUE;
        assertEquals(Integer.MAX_VALUE, forge.getEnergyStored());
        assertEquals(received, buffer.storedEnergy());
        assertEquals(Integer.MAX_VALUE, forge.receiveEnergy(Integer.MAX_VALUE, true));
        assertEquals(received, buffer.extractEnergy(Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(received, buffer.storedEnergy());
        assertEquals(2, notifications[0]);

        assertEquals(0, forge.extractEnergy(Integer.MAX_VALUE, false));
        assertEquals(received, buffer.extractEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(0, buffer.storedEnergy());
        assertEquals(3, notifications[0]);
    }

    @Test
    void restoreNearCapacityThenFillAndConsumeDoesNotOverflow() {
        var buffer = new EnergyBuffer(Long.MAX_VALUE, () -> {
        });
        buffer.restore(Long.MAX_VALUE - 3);
        assertEquals(3, buffer.receiveEnergy(Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(3, buffer.receiveEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(Long.MAX_VALUE, buffer.extractEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(0, buffer.storedEnergy());
    }

    @Test
    void transferLimitsApplyToEachOperationAndInvalidationRetainsOnlySavedBalance() {
        int[] notifications = {0};
        var buffer = new EnergyBuffer(100, 11, 7, () -> notifications[0]++);
        assertEquals(11, buffer.receiveEnergy(100, Actionable.SIMULATE));
        assertEquals(11, buffer.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(11, buffer.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(7, buffer.extractEnergy(100, Actionable.SIMULATE));
        assertEquals(7, buffer.extractEnergy(100, Actionable.MODULATE));
        assertEquals(15, buffer.storedEnergy());
        assertEquals(3, notifications[0]);

        buffer.setTransferLimits(3, 2);
        assertEquals(3, buffer.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(2, buffer.extractEnergy(100, Actionable.MODULATE));
        buffer.invalidate();
        assertEquals(0, buffer.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(0, buffer.extractEnergy(100, Actionable.MODULATE));
        assertEquals(0, buffer.storedEnergy());
        assertEquals(16, buffer.savedEnergy());
        // Changed availability (limits and invalidation) also publishes the save/sync signal.
        assertEquals(7, notifications[0]);
    }
}
