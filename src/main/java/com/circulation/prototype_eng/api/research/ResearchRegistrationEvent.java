package com.circulation.prototype_eng.api.research;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

/** Java research declarations, after computing resources freeze and before recipes are registered. */
public final class ResearchRegistrationEvent extends Event {
    private final ResearchRegistry registry;

    public ResearchRegistrationEvent(ResearchRegistry registry) { this.registry = Objects.requireNonNull(registry); }

    @SuppressWarnings("unused") // Addons declare their research trees through this event.
    public ResearchRegistry registry() { return registry; }
}
