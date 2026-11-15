package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import net.minecraftforge.fml.common.eventhandler.Event;


/**
 * Fired once on the Forge event bus during post-init, before recipes and JEI bindings are frozen.
 */
public final class RecipeRegistrationEvent extends Event {
    private final RecipeRegistry registry;
    private final NetworkRecipeExtensions networkExtensions;

    public RecipeRegistrationEvent(RecipeRegistry registry, NetworkRecipeExtensions networkExtensions) {
        this.registry = registry;
        this.networkExtensions = networkExtensions;
    }

    /**
     * Downstream mods register their pools and recipes through this event's registry.
     */
    @SuppressWarnings("unused")
    public RecipeRegistry registry() {
        return registry;
    }

    /**
     * Registers ordered per-recipe network requirements before the complete catalog is published.
     */
    @SuppressWarnings("unused")
    public NetworkRecipeExtensions networkExtensions() {
        return networkExtensions;
    }
}
