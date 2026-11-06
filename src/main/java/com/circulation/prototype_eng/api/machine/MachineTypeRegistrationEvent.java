package com.circulation.prototype_eng.api.machine;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

/** Fired before block registration so downstream declarations can generate their controllers. */
public final class MachineTypeRegistrationEvent extends Event {
    private final MachineTypeRegistry registry;

    public MachineTypeRegistrationEvent(MachineTypeRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public MachineTypeRegistry registry() { return registry; }
}
