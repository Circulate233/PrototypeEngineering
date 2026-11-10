package com.circulation.prototype_eng.api.recipe;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Complete already-loaded Java/CT declarations. Binding a key never proves another closure equivalent.
 */
public final class RecipeDeclarations {
    public static final RecipeDeclarations INSTANCE = new RecipeDeclarations();
    private final Object2ObjectOpenHashMap<RecipeDeclarationKey, RecipeRegistry.Snapshot> declarations = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<RecipeRegistry.Snapshot, RecipeDeclarationKey> keys = new Reference2ObjectOpenHashMap<>();
    private final ObjectArrayList<BiConsumer<RecipeDeclarationKey, RecipeRegistry.Snapshot>> listeners = new ObjectArrayList<>();

    public void register(RecipeDeclarationKey key, RecipeRegistry.Snapshot snapshot) {
        Objects.requireNonNull(key);
        RecipeDefinitionManifest.describe(snapshot);
        ObjectArrayList<BiConsumer<RecipeDeclarationKey, RecipeRegistry.Snapshot>> callbacks;
        synchronized (this) {
            var previous = declarations.get(key);
            if (previous != null) {
                if (previous != snapshot)
                    throw new IllegalArgumentException("Recipe declaration key already binds another snapshot: " + key);
                return;
            }
            var previousKey = keys.get(snapshot);
            if (previousKey != null && !previousKey.equals(key))
                throw new IllegalArgumentException("Recipe snapshot already has a declaration key: " + previousKey);
            declarations.put(key, snapshot);
            keys.put(snapshot, key);
            callbacks = new ObjectArrayList<>(listeners);
        }
        Throwable failure = null;
        for (var callback : callbacks) {
            try {
                callback.accept(key, snapshot);
            } catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught;
                else if (failure != caught) failure.addSuppressed(caught);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    public synchronized @Nullable RecipeRegistry.Snapshot get(RecipeDeclarationKey key) {
        return declarations.get(key);
    }

    public synchronized @Nullable RecipeDeclarationKey key(RecipeRegistry.Snapshot snapshot) {
        return keys.get(snapshot);
    }

    /**
     * Lifecycle services retain a permanent narrow listener; callbacks run outside the table monitor.
     */
    public synchronized void onRegistered(BiConsumer<RecipeDeclarationKey, RecipeRegistry.Snapshot> callback) {
        listeners.add(Objects.requireNonNull(callback));
    }
}
