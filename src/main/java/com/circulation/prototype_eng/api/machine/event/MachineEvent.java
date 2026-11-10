package com.circulation.prototype_eng.api.machine.event;

import com.circulation.prototype_eng.api.machine.MachineInstance;

/**
 * Open event contract for machine systems; independent of Forge recipe registration events.
 */
public interface MachineEvent {
    MachineInstance machine();
}
