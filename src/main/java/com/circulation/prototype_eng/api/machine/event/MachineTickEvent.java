package com.circulation.prototype_eng.api.machine.event;

import com.circulation.prototype_eng.api.machine.MachineInstance;

public sealed interface MachineTickEvent extends MachineEvent {
    record Pre(MachineInstance machine) implements MachineTickEvent {}
    record Normal(MachineInstance machine) implements MachineTickEvent {}
    record Post(MachineInstance machine) implements MachineTickEvent {}
}
