package com.circulation.prototype_eng.api.client.render;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

/**
 * Client startup event carrying the exact registry consumed by native machine renderers.
 */
public final class MachineRenderRegistrationEvent extends Event {
    private final MachineRenderBindings bindings;

    public MachineRenderRegistrationEvent(MachineRenderBindings bindings) {
        this.bindings = Objects.requireNonNull(bindings);
    }

    public MachineRenderBindings bindings() {
        return bindings;
    }
}
