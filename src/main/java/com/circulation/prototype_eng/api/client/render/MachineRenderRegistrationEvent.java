package com.circulation.prototype_eng.api.client.render;

import net.minecraftforge.fml.common.eventhandler.Event;


/**
 * Client startup event carrying the exact registry consumed by native machine renderers.
 */
public final class MachineRenderRegistrationEvent extends Event {
    private final MachineRenderBindings bindings;

    public MachineRenderRegistrationEvent(MachineRenderBindings bindings) {
        this.bindings = bindings;
    }

    public MachineRenderBindings bindings() {
        return bindings;
    }
}
