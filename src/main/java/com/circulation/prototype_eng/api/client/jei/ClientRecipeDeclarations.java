package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;


/**
 * Separate client objects and prepared display providers; server JVM objects are never a resolution source.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
@SideOnly(Side.CLIENT)
public final class ClientRecipeDeclarations {
    public static final ClientRecipeDeclarations INSTANCE = new ClientRecipeDeclarations();
    private final Thread owner = Thread.currentThread();
    private final Object2ObjectOpenHashMap<RecipeDeclarationKey, Binding> declarations = new Object2ObjectOpenHashMap<>();
    private final ObjectArrayList<Runnable> listeners = new ObjectArrayList<>();

    public void register(RecipeDeclarationKey key, RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays) {
        requireOwner();
        RecipeDefinitionManifest.describe(snapshot);
        var previous = declarations.get(key);
        if (previous != null) {
            if (previous.declaration().snapshot() != snapshot || previous.source() != displays)
                throw new IllegalArgumentException("Client recipe declaration key already binds different local data: " + key);
            if (!displays.sameProviders(previous.declaration().displays()))
                throw new IllegalArgumentException("Client recipe declaration providers changed without a new key: " + key);
            return;
        }
        declarations.put(key, new Binding(displays, new Declaration(snapshot, displays.frozenCopy())));
        Throwable failure = null;

        for (int index = 0, size = listeners.size(); index < size; index++) {
            var listener = listeners.get(index);
            try {
                listener.run();
            } catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught;
                else if (failure != caught) failure.addSuppressed(caught);
            }
        }

        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    public @Nullable Declaration get(RecipeDeclarationKey key) {
        requireOwner();
        var binding = declarations.get(key);
        return binding == null ? null : binding.declaration();
    }

    public void onRegistered(Runnable callback) {
        requireOwner();
        listeners.add(callback);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Client recipe declarations require their owner");
    }

    public record Declaration(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays) {
    }

    private record Binding(RecipeJeiDisplays source, Declaration declaration) {
    }
}
