package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Delays a composed provider without changing its matching strategy or exact recipe identities. */
public final class DeferredRecipePool implements RecipePool {
    private final ResourceLocation id;
    private final String translationKey;
    private final Supplier<? extends RecipePool> source;
    private volatile @Nullable RecipePool initialized;
    private @Nullable Throwable failure;
    private boolean initializing;

    public DeferredRecipePool(ResourceLocation id, String translationKey, Supplier<? extends RecipePool> source) {
        this.id = Objects.requireNonNull(id, "id");
        if (translationKey == null || translationKey.isBlank()) throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        this.source = Objects.requireNonNull(source, "source");
    }

    private RecipePool data() {
        var current = initialized;
        return current == null ? initialize() : current;
    }

    private synchronized RecipePool initialize() {
        if (initialized != null) return initialized;
        if (failure != null) throw new IllegalStateException("Recipe provider previously failed: " + id, failure);
        if (initializing) throw new IllegalStateException("Recursive recipe provider: " + id);
        initializing = true;
        try {
            var completed = Objects.requireNonNull(source.get(), "Recipe pool provider");
            if (!id.equals(completed.id()) || !translationKey.equals(completed.translationKey()))
                throw new IllegalArgumentException("Deferred recipe provider changed its metadata: " + id);
            initialized = completed;
            return completed;
        } catch (RuntimeException | Error ex) {
            failure = ex;
            LogManager.getLogger().error("Failed to create deferred recipe pool {}", id, ex);
            throw ex;
        } finally {
            initializing = false;
        }
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public String translationKey() {
        return translationKey;
    }

    @Override
    public List<MachineRecipe> recipes() {
        return data().recipes();
    }

    @Override
    public List<MachineType> machines() {
        return List.of();
    }

    @Override
    public MachineRecipe prepareRecipe(MachineRecipe recipe) {
        return data().prepareRecipe(recipe);
    }

    @Override
    public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        return data().match(inventory, parallelism);
    }

    @Override
    public RecipeCandidateIndex.Cursor beginMatch(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        return data().beginMatch(inventory, parallelism, groups);
    }

    @Override
    public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        return data().beginCandidates(machine, groups);
    }

    @Override
    public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return data().freeze(recipes, machines);
    }
}
