package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.storage.HatchMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForgeEnergyPortTest {
    @Test
    void physicalDirectionsAllowReverseMachineOperationsOnTheSameBalance() {
        var input = new EnergyBuffer(100, 20, 20, () -> {});
        var inputPort = new ForgeEnergyPort(input, HatchMode.INPUT);
        assertEquals(20, inputPort.receiveEnergy(100, false));
        assertEquals(0, inputPort.extractEnergy(100, false));
        assertEquals(20, input.extractEnergy(100, Actionable.MODULATE));

        var output = new EnergyBuffer(100, 30, 20, () -> {});
        var outputPort = new ForgeEnergyPort(output, HatchMode.OUTPUT);
        assertEquals(0, outputPort.receiveEnergy(100, false));
        assertEquals(30, output.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(20, outputPort.extractEnergy(100, false));
        assertEquals(10, output.storedEnergy());
    }

    @Test
    void mixedPortUsesLiveLimitsWithoutTurningSimulationIntoAReservation() {
        int[] notifications = {0};
        var buffer = new EnergyBuffer(100, 13, 5, () -> notifications[0]++);
        var port = new ForgeEnergyPort(buffer, HatchMode.MIXED);
        assertEquals(13, port.receiveEnergy(100, true));
        assertEquals(13, port.receiveEnergy(100, false));
        assertEquals(13, port.receiveEnergy(100, false));
        assertEquals(5, port.extractEnergy(100, true));
        assertEquals(26, buffer.storedEnergy());
        assertEquals(2, notifications[0]);

        buffer.setTransferLimits(2, 9);
        assertEquals(2, port.receiveEnergy(100, false));
        assertEquals(9, port.extractEnergy(100, false));
        assertEquals(19, buffer.storedEnergy());
        // Limit changes affect reservation availability and publish one additional signal.
        assertEquals(5, notifications[0]);
        buffer.invalidate();
        assertEquals(0, port.receiveEnergy(100, false));
        assertEquals(0, port.extractEnergy(100, false));
        assertEquals(19, buffer.savedEnergy());
    }
}
