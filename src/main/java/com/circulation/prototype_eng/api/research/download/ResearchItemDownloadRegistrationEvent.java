package com.circulation.prototype_eng.api.research.download;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

/**
 * Java item download registration, after research freeze and before server consumers become active.
 */
@SuppressWarnings("unused") // Public Forge event posted by the mod loading lifecycle.
public final class ResearchItemDownloadRegistrationEvent extends Event {
    private final ResearchItemDownloadRegistry registry;

    public ResearchItemDownloadRegistrationEvent(ResearchItemDownloadRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Item download registry");
    }

    public ResearchItemDownloadRegistry registry() {
        return registry;
    }
}
