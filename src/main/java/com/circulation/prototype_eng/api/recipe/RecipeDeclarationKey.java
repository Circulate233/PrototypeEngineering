package com.circulation.prototype_eng.api.recipe;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Explicit identity of a complete locally resolved declaration set, independent of a server session.
 */
@SuppressWarnings("unused") // Public Java declaration identity shared with separately installed client declarations.
public record RecipeDeclarationKey(ResourceLocation id, String revision) {
    public RecipeDeclarationKey {
        Objects.requireNonNull(id, "declaration ID");
        if (Objects.requireNonNull(revision, "revision").isBlank()) {
            throw new IllegalArgumentException("Declaration revision must not be blank");
        }
    }
}
