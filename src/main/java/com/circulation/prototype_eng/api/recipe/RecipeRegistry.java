package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.circulation.prototype_eng.api.machine.MachineType;

/**
 * Register during RecipeRegistrationEvent; frozen definitions are available after mod post-init.
 */
public final class RecipeRegistry {
    public static final RecipeRegistry INSTANCE = new RecipeRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, PoolRegistration> registrations = new Object2ObjectLinkedOpenHashMap<>();
    private volatile Frozen frozen;

    public void register(RecipePool pool) {
        requireRegistration();
        Objects.requireNonNull(pool, "pool");
        var id = Objects.requireNonNull(pool.id(), "pool id");
        if (registrations.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate recipe pool: " + id);
        }
        registrations.put(id, new PoolRegistration(pool));
    }

    public void registerPool(ResourceLocation id, String translationKey) {
        register(new DefaultRecipePool(id, translationKey, ObjectLists.emptyList()));
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
        freeze(List.of());
        }

    /** Resolve every static machine reference before publishing pools, reverse bindings or indexes. */
    public void freeze(List<MachineType> machines) {
        if (frozen != null) {
            throw new IllegalStateException("Recipe registry already frozen");
        }
        var users = new Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<MachineType>>();
        var machineIds = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineType>();
        for (var machine : Objects.requireNonNull(machines, "machine declarations")) {
            Objects.requireNonNull(machine, "machine declaration");
            if (machineIds.putIfAbsent(machine.id(), machine) != null) {
                throw new IllegalArgumentException("Duplicate machine declaration: " + machine.id());
            }
            for (var poolId : machine.recipePoolIds()) {
                if (!registrations.containsKey(poolId)) {
                    throw new IllegalArgumentException("Unknown recipe pool " + poolId + " for machine " + machine.id());
                }
                users.computeIfAbsent(poolId, _ -> new ObjectArrayList<>()).add(machine);
            }
        }
        var pools = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>();
        for (var entry : registrations.object2ObjectEntrySet()) {
            var registration = entry.getValue();
            var recipes = ObjectLists.unmodifiable(new ObjectArrayList<>(registration.recipes.values()));
            var boundMachines = ObjectLists.unmodifiable(new ObjectArrayList<>(users.getOrDefault(entry.getKey(), new ObjectArrayList<>())));
            var pool = Objects.requireNonNull(registration.source.freeze(recipes, boundMachines), "Pool freeze result");
            if (!entry.getKey().equals(pool.id()) || !registration.translationKey.equals(pool.translationKey())
                || !sameInstances(recipes, pool.recipes()) || !sameInstances(boundMachines, pool.machines())) {
                throw new IllegalArgumentException("Pool freeze changed canonical definition: " + entry.getKey());
            }
            pools.put(entry.getKey(), pool);
        }
        var ordered = new ObjectArrayList<>(pools.values());
        ordered.sort(Comparator.comparing(pool -> pool.id().toString()));
        var frozenPools = ObjectLists.unmodifiable(ordered);
        var catalog = new RecipeCatalog(frozenPools);
        frozen = new Frozen(Object2ObjectMaps.unmodifiable(pools), frozenPools, catalog);
        registrations.clear();
    }

    /** Downstream mods enumerate the frozen pools after registration has finished. */
    @SuppressWarnings({"unused", "UnusedReturnValue"}) // External enumeration; internal tests only probe readiness failure.
    public List<RecipePool> pools() {
        return requireFrozen().orderedPools();
    }

    /** Shared canonical recipe indexes; available only after the whole registry freezes successfully. */
    public RecipeCatalog catalog() {
        return requireFrozen().catalog();
    }

    @Nullable
    public RecipePool get(ResourceLocation id) {
        return requireFrozen().pools().get(id);
    }

    private Frozen requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Recipe registry is not ready");
        }
        return current;
    }

    private void requireRegistration() {
        if (frozen != null) {
            throw new IllegalStateException("Recipe registration has ended");
        }
    }

    private static boolean sameInstances(List<?> expected, List<?> actual) {
        if (actual == null || expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (expected.get(i) != actual.get(i)) return false;
        return true;
    }

    private record Frozen(Map<ResourceLocation, RecipePool> pools, List<RecipePool> orderedPools, RecipeCatalog catalog) { }

    private static final class PoolRegistration {
        final RecipePool source;
        final String translationKey;
        final Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe> recipes = new Object2ObjectLinkedOpenHashMap<>();

        PoolRegistration(RecipePool pool) {
            source = pool;
            translationKey = pool.translationKey();
            if (translationKey == null || translationKey.isBlank())
                throw new IllegalArgumentException("Pool title is required");
            for (MachineRecipe recipe : Objects.requireNonNull(pool.recipes(), "pool recipes")) {
                Objects.requireNonNull(recipe, "pool recipe");
                if (recipes.putIfAbsent(recipe.id(), recipe) != null)
                    throw new IllegalArgumentException("Duplicate recipe in pool " + pool.id() + ": " + recipe.id());
            }
        }
    }
}
