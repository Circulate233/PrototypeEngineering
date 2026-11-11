package com.circulation.prototype_eng.api.research;

import net.minecraftforge.fml.common.eventhandler.Event;
import java.util.Objects;

/** Fired during PostInit before the server owner can capture its immutable configuration. */
public final class ResearchServerConfigurationEvent extends Event {
    private final ResearchServerConfiguration configuration;
    public ResearchServerConfigurationEvent(ResearchServerConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration);
    }
    public ResearchServerConfiguration configuration() { return configuration; }
}
