package com.circulation.prototype_eng.api.machine;

import net.minecraftforge.fml.common.eventhandler.Event;

/** Fired during Forge block registration, before the controller definitions are frozen. */
public final class ParallelControllerRegistrationEvent extends Event {
    public ParallelControllerRegistry registry() {
        return ParallelControllerRegistry.INSTANCE;
    }
}
