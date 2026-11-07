package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.RecipePool;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Client-owned binding. No JEI reference is stored in the common recipe registry or pool.
 */
@SideOnly(Side.CLIENT)
public class RecipePoolJeiRegistry {
    public static final RecipePoolJeiRegistry INSTANCE = new RecipePoolJeiRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePoolJeiPage> custom = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ItemStack>> catalysts = new Object2ObjectLinkedOpenHashMap<>();
    private Map<ResourceLocation, RecipePoolJeiPage> pages;
    private boolean binding;

    /**
     * Register an independent interface instance during client init or RecipePoolJeiRegistrationEvent.
     */
    public void register(ResourceLocation poolId, RecipePoolJeiPage page) {
        requireRegistration();
        Objects.requireNonNull(poolId);
        Objects.requireNonNull(page);
        if (!poolId.equals(page.pool().id())) {
            throw new IllegalArgumentException("JEI page belongs to a different recipe pool");
        }
        if (custom.putIfAbsent(poolId, page) != null) {
            throw new IllegalArgumentException("Duplicate JEI page binding: " + poolId);
        }
    }

    /**
     * Additional navigation entries use the same pool category; no fake recipe output is added.
     */
    @SuppressWarnings("unused") // Public addon navigation binding during the client event.
    public void catalyst(ResourceLocation poolId, ItemStack item) {
        requireRegistration();
        if (Objects.requireNonNull(item).isEmpty()) {
            throw new IllegalArgumentException("JEI catalyst is empty");
        }
        catalysts.computeIfAbsent(Objects.requireNonNull(poolId), _ -> new ObjectArrayList<>()).add(item.copy());
    }

    /**
     * Publish only after all bindings validate. Default creation runs once for each unbound pool.
     */
    public void freeze(List<RecipePool> pools, Function<RecipePool, ? extends RecipePoolJeiPage> defaultFactory) {
        requireRegistration();
        Objects.requireNonNull(defaultFactory);
        binding = true;
        try {
            var known = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>();
            for (var pool : pools) {
                if (known.putIfAbsent(Objects.requireNonNull(pool).id(), pool) != null) {
                    throw new IllegalArgumentException("Duplicate frozen recipe pool: " + pool.id());
                }
            }
            for (var id : custom.keySet()) {
                if (!known.containsKey(id)) {
                    throw new IllegalArgumentException("Custom JEI page has unknown recipe pool: " + id);
                }
            }
            for (var id : catalysts.keySet()) {
                if (!known.containsKey(id)) {
                    throw new IllegalArgumentException("JEI catalyst has unknown recipe pool: " + id);
                }
            }
            var resolved = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePoolJeiPage>();
            for (var pool : pools) {
                var page = custom.get(pool.id());
                if (page == null) {
                    page = Objects.requireNonNull(defaultFactory.apply(pool), "Default JEI page returned null");
                }
                if (page.pool() != pool || !RecipePoolJeiPage.uid(pool.id()).equals(page.getUid())) {
                    throw new IllegalArgumentException("JEI page must retain its canonical pool and stable pool UID: " + pool.id());
                }
                resolved.put(pool.id(), page);
            }
            pages = Object2ObjectMaps.unmodifiable(resolved);
            custom.clear();
        } finally {
            binding = false;
        }
    }

    public List<RecipePoolJeiPage> pages() {
        requireFrozen();
        return ObjectLists.unmodifiable(new ObjectArrayList<>(pages.values()));
    }

    public List<ItemStack> catalysts(ResourceLocation poolId) {
        requireFrozen();
        if (!pages.containsKey(poolId)) {
            throw new IllegalArgumentException("Unknown JEI recipe pool: " + poolId);
        }
        var result = new ObjectArrayList<ItemStack>();
        var entries = catalysts.get(poolId);
        if (entries != null) {
            for (var stack : entries) {
                result.add(stack.copy());
            }
        }
        return ObjectLists.unmodifiable(result);
    }

    private void requireRegistration() {
        if (pages != null || binding) {
            throw new IllegalStateException("JEI page registration has ended or is currently binding");
        }
    }

    private void requireFrozen() {
        if (pages == null) {
            throw new IllegalStateException("JEI recipe pools are not ready");
        }
    }
}
