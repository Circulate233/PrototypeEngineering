package com.circulation.prototype_eng.api.recipe;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Register during RecipeRegistrationEvent; frozen definitions are available after mod post-init.
 */
public final class RecipeRegistry {
    public static final RecipeRegistry INSTANCE = new RecipeRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, PoolRegistration> registrations = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool> pools = new Object2ObjectLinkedOpenHashMap<>();
    private List<RecipePool> frozenPools;

    public void register(RecipePool pool) {
        requireRegistration();
        Objects.requireNonNull(pool, "pool");
        if (registrations.containsKey(pool.id())) {
            throw new IllegalArgumentException("Duplicate recipe pool: " + pool.id());
        }
        registrations.put(pool.id(), new PoolRegistration(pool));
    }

    public void registerPool(ResourceLocation id, String translationKey) {
        register(new RecipePool(id, translationKey, ObjectLists.emptyList()));
    }

    /**
     * Adds to a previously declared pool, including one owned by another addon. Recipe order is priority.
     */
    public void registerRecipe(ResourceLocation poolId, MachineRecipe recipe) {
        requireRegistration();
        Objects.requireNonNull(recipe, "recipe");
        var pool = registrations.get(poolId);
        if (pool == null) {
            throw new IllegalArgumentException("Unknown recipe pool: " + poolId);
        }
        if (pool.recipes.containsKey(recipe.id())) {
            throw new IllegalArgumentException("Duplicate recipe in pool " + poolId + ": " + recipe.id());
        }
        pool.recipes.put(recipe.id(), recipe);
    }

    /**
     * Called by the lifecycle owner, once all Java registrations have been dispatched.
     */
    public void freeze() {
        if (frozenPools != null) {
            throw new IllegalStateException("Recipe registry already frozen");
        }
        for (var entry : registrations.object2ObjectEntrySet()) {
            var registration = entry.getValue();
            pools.put(entry.getKey(), new RecipePool(entry.getKey(), registration.translationKey,
                new ObjectArrayList<>(registration.recipes.values())));
        }
        var ordered = new ObjectArrayList<>(pools.values());
        ordered.sort(Comparator.comparing(pool -> pool.id().toString()));
        frozenPools = ObjectLists.unmodifiable(ordered);
        registrations.clear();
    }

    /** Downstream mods enumerate the frozen pools after registration has finished. */
    @SuppressWarnings("unused")
    public List<RecipePool> pools() {
        requireFrozen();
        return frozenPools;
    }

    @Nullable
    public RecipePool get(ResourceLocation id) {
        requireFrozen();
        return pools.get(id);
    }

    private void requireFrozen() {
        if (frozenPools == null) {
            throw new IllegalStateException("Recipe registry is not ready");
        }
    }

    private void requireRegistration() {
        if (frozenPools != null) {
            throw new IllegalStateException("Recipe registration has ended");
        }
    }

    private static final class PoolRegistration {
        final String translationKey;
        final Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe> recipes = new Object2ObjectLinkedOpenHashMap<>();

        PoolRegistration(RecipePool pool) {
            translationKey = pool.translationKey();
            for (MachineRecipe recipe : pool.recipes()) {
                recipes.put(recipe.id(), recipe);
            }
        }
    }
}
