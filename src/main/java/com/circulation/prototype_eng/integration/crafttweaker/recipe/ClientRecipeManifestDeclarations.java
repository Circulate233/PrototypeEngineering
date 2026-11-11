package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.Objects;

/** Common startup data only; a dedicated server never links a client event or display provider. */
final class ClientRecipeManifestDeclarations {
    private static final Object2ObjectLinkedOpenHashMap<RecipeDeclarationKey, Declaration> PENDING = new Object2ObjectLinkedOpenHashMap<>();
    private static List<Declaration> frozen;

    private ClientRecipeManifestDeclarations() {
    }

    static synchronized void add(RecipeDeclarationKey key, List<RecipeRegistry.Definition> definitions, List<RecipeProcessNbt> codecs) {
        if (frozen != null) throw new IllegalStateException("Client recipe declaration registration has closed");
        var declaration = new Declaration(Objects.requireNonNull(key), ObjectLists.unmodifiable(new ObjectArrayList<>(definitions)),
            ObjectLists.unmodifiable(new ObjectArrayList<>(codecs)));
        var previous = PENDING.putIfAbsent(key, declaration);
        if (previous != null && !previous.equals(declaration))
            throw new IllegalArgumentException("Client declaration key already describes other concrete definitions: " + key);
    }

    static synchronized List<Declaration> freeze() {
        if (frozen == null) {
            frozen = ObjectLists.unmodifiable(new ObjectArrayList<>(PENDING.values()));
            PENDING.clear();
        }
        return frozen;
    }

    record Declaration(RecipeDeclarationKey key, List<RecipeRegistry.Definition> definitions, List<RecipeProcessNbt> codecs) {
    }
}
