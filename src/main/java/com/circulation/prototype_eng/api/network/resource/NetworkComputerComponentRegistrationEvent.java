package com.circulation.prototype_eng.api.network.resource;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

/**
 * Java item providers register after computing metadata freezes and before dependent definitions load.
 */
public final class NetworkComputerComponentRegistrationEvent extends Event {
    private final NetworkComputerComponentRegistry registry;

    public NetworkComputerComponentRegistrationEvent(NetworkComputerComponentRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @SuppressWarnings("unused") // Java addon registration entry.
    public NetworkComputerComponentRegistry registry() {
        return registry;
    }
}
