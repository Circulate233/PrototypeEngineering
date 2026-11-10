package com.circulation.prototype_eng.api.structure.json;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

/**
 * Fired in PostInit before any JSON predicate or validator is resolved.
 */
public final class StructurePredicateRegistrationEvent extends Event {
    private final StructurePredicateRegistry registry;

    public StructurePredicateRegistrationEvent(StructurePredicateRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @SuppressWarnings("unused") // Downstream PostInit event handlers register their own factories.
    public StructurePredicateRegistry registry() {
        return registry;
    }
}
