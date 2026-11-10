package com.circulation.prototype_eng.api.recipe;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

/**
 * After the canonical Java directory freezes, declare its explicit complete behavior/display identity.
 */
public final class RecipeDeclarationRegistrationEvent extends Event {
    private final RecipeDeclarations declarations;
    private final RecipeRegistry.Snapshot snapshot;

    public RecipeDeclarationRegistrationEvent(RecipeDeclarations declarations, RecipeRegistry.Snapshot snapshot) {
        this.declarations = Objects.requireNonNull(declarations);
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    public RecipeDeclarations declarations() {
        return declarations;
    }

    public RecipeRegistry.Snapshot snapshot() {
        return snapshot;
    }
}
