package com.circulation.prototype_eng.api.network.resource;

import net.minecraftforge.fml.common.eventhandler.Event;

/** Fired once during post-init, before recipe and machine runtime registration. */
public final class NetworkResourceRegistrationEvent extends Event {
    private final NetworkResourceRegistry registry;

    public NetworkResourceRegistrationEvent(NetworkResourceRegistry registry) { this.registry = registry; }

    /** Java addons declare computing dimensions here before dependent definitions are resolved. */
    @SuppressWarnings("unused")
    public NetworkResourceRegistry registry() { return registry; }
}
