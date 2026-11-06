package com.circulation.prototype_eng.api.recipe;

import net.minecraftforge.fml.common.eventhandler.Event;

/** Fired once on the Forge event bus during post-init, before recipes and JEI bindings are frozen. */
public final class RecipeRegistrationEvent extends Event {
    private final RecipeRegistry registry;

    public RecipeRegistrationEvent(RecipeRegistry registry) { this.registry = registry; }
    public RecipeRegistry registry() { return registry; }
}
