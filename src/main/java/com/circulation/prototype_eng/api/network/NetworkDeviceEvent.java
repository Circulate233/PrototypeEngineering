package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.event.MachineEvent;

/**
 * Modifies this device's candidate values on the server owner thread. Repeatable callbacks must not mutate other devices or charge costs.
 */
public record NetworkDeviceEvent(MachineInstance machine, NetworkData network,
                                 NetworkResourceState values) implements MachineEvent {
}
