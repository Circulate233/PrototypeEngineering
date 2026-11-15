package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Client-owned binding. No JEI reference is stored in the common recipe registry or pool.
 * Returned lists and catalyst stacks are borrowed read-only until the next registration generation.
 */
@SideOnly(Side.CLIENT)
public class RecipePoolJeiRegistry {
    public static final RecipePoolJeiRegistry INSTANCE = new RecipePoolJeiRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePoolJeiPage> custom = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, JeiRenderOptions> options = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, JeiRenderOptions> clientOptions = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ItemStack>> catalysts = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePoolJeiPage> clientCustom = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ItemStack>> clientCatalysts = new Object2ObjectLinkedOpenHashMap<>();
    private Map<ResourceLocation, RecipePoolJeiPage> pages;
    private List<RecipePoolJeiPage> pageList;
    private Map<ResourceLocation, RecipePool> registeredPools;
    private Map<ResourceLocation, String> externalPages;
    private boolean binding;
    private boolean started;

    public static @Nullable String externalPage(RecipePool pool, Set<String> existingCategoryIds) {
        if (!pool.hasExistingJeiPage()) return null;
        var id = pool.existingJeiPageId();
        return id != null && !id.isBlank() && existingCategoryIds.contains(id) ? id : null;
    }

    public static List<MachineRecipe> recipesForOwnPages(List<RecipePool> pools, Set<String> existingCategoryIds) {
        var result = new ObjectArrayList<MachineRecipe>();
        for (int index2 = 0; index2 < pools.size(); index2++) {
            var pool = pools.get(index2);
            if (externalPage(pool, existingCategoryIds) == null) result.addAll(pool.recipes());
        }
        return result;
    }

    /**
     * HEI rebuilds categories after a language/resource reload. Retain declarations made before the
     * first category event, then reopen only that event's registrations and rebuild its pages.
     */
    public void beginRegistration() {
        if (binding) throw new IllegalStateException("JEI pages are currently binding");
        if (!started) {
            clientCustom.putAll(custom);
            clientOptions.putAll(options);
            clientCatalysts.putAll(catalysts);
            started = true;
        }
        pages = null;
        pageList = null;
        registeredPools = null;
        externalPages = null;
        custom.clear();
        options.clear();
        options.putAll(clientOptions);
        custom.putAll(clientCustom);
        catalysts.clear();
        catalysts.putAll(clientCatalysts);
    }

    /**
     * Register an independent interface instance during client init or RecipePoolJeiRegistrationEvent.
     */
    public void register(ResourceLocation poolId, RecipePoolJeiPage page) {
        requireRegistration();
        if (!poolId.equals(page.pool().id())) {
            throw new IllegalArgumentException("JEI page belongs to a different recipe pool");
        }
        if (custom.putIfAbsent(poolId, page) != null) {
            throw new IllegalArgumentException("Duplicate JEI page binding: " + poolId);
        }
    }

    /**
     * Display parameters belong to this category generation, independently of server pool data.
     */
    public void renderOptions(ResourceLocation poolId, JeiRenderOptions settings) {
        requireRegistration();
        if (options.putIfAbsent(poolId, settings) != null)
            throw new IllegalArgumentException("Duplicate JEI render options: " + poolId);
    }

    public JeiRenderOptions renderOptions(ResourceLocation poolId) {
        return options.getOrDefault(poolId, JeiRenderOptions.defaults());
    }

    /**
     * Additional navigation entries use the same pool category; no fake recipe output is added.
     */
    @SuppressWarnings("unused") // Public addon navigation binding during the client event.
    public void catalyst(ResourceLocation poolId, ItemStack item) {
        requireRegistration();
        if (item.isEmpty()) {
            throw new IllegalArgumentException("JEI catalyst is empty");
        }
        var entries = catalysts.get(poolId);
        if (entries == null) {
            entries = new ObjectArrayList<>();
            catalysts.put(poolId, entries);
        } else if (started && entries == clientCatalysts.get(poolId)) {
            entries = new ObjectArrayList<>(entries);
            catalysts.put(poolId, entries);
        }
        entries.add(item);
    }

    /**
     * Publish only after all bindings validate. Default creation runs once for each unbound pool.
     */
    public void freeze(List<RecipePool> pools, Function<RecipePool, ? extends RecipePoolJeiPage> defaultFactory) {
        freeze(pools, defaultFactory, null);
    }

    /**
     * During a native runtime rebuild, retained independent pages may prepare their accurate new pool.
     */
    public void freeze(List<RecipePool> pools, Function<RecipePool, ? extends RecipePoolJeiPage> defaultFactory,
                       @Nullable RecipeJeiDisplays.Snapshot descriptions) {
        freeze(pools, defaultFactory, descriptions, Set.of());
    }

    /**
     * Resolve external categories only from HEI's completed registration or explicit disabled IDs.
     */
    public void freeze(List<RecipePool> pools, Function<RecipePool, ? extends RecipePoolJeiPage> defaultFactory,
                       @Nullable RecipeJeiDisplays.Snapshot descriptions, Set<String> existingCategoryIds) {
        requireRegistration();
        binding = true;
        try {
            var known = indexPools(pools);
            for (var id : custom.keySet()) {
                if (!known.containsKey(id)) {
                    throw new IllegalArgumentException("Custom JEI page has unknown recipe pool: " + id);
                }
            }
            for (var id : options.keySet()) {
                if (!known.containsKey(id))
                    throw new IllegalArgumentException("JEI render options have unknown pool: " + id);
            }
            for (var id : catalysts.keySet()) {
                if (!known.containsKey(id)) {
                    throw new IllegalArgumentException("JEI catalyst has unknown recipe pool: " + id);
                }
            }
            var resolved = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePoolJeiPage>();
            var external = new Object2ObjectLinkedOpenHashMap<ResourceLocation, String>();
            for (int index6 = 0; index6 < pools.size(); index6++) {
                var pool = pools.get(index6);
                var target = externalPage(pool, existingCategoryIds);
                if (target != null) {
                    external.put(pool.id(), target);
                    continue;
                }
                var page = custom.get(pool.id());
                if (page == null) {
                    page = defaultFactory.apply(pool);
                } else if (page.pool() != pool && descriptions != null) {
                    var update = page.prepareUpdate(pool, descriptions);
                    if (update == null)
                        throw new IllegalStateException("Retained JEI page does not support definition updates: " + pool.id());
                    if (update.page() != page || update.pool() != pool)
                        throw new IllegalArgumentException("Retained JEI page prepared foreign data");
                    update.apply();
                }
                if (page.pool() != pool || !RecipePoolJeiPage.uid(pool.id()).equals(page.getUid())) {
                    throw new IllegalArgumentException("JEI page must retain its canonical pool and stable pool UID: " + pool.id());
                }
                resolved.put(pool.id(), page);
            }
            pages = resolved;
            pageList = new ObjectArrayList<>(resolved.values());
            registeredPools = known;
            externalPages = external;
            custom.clear();
        } finally {
            binding = false;
        }
    }

    public List<RecipePoolJeiPage> pages() {
        requireFrozen();
        return pageList;
    }

    public Map<ResourceLocation, String> externalPages() {
        requireFrozen();
        return externalPages;
    }

    /**
     * Recipes drawn by this plugin; externally owned pools contribute catalysts only.
     */
    public List<MachineRecipe> ownedRecipes(List<RecipePool> pools) {
        requireFrozen();
        requireSamePools(pools);
        var result = new ObjectArrayList<MachineRecipe>();
        for (int index1 = 0; index1 < pools.size(); index1++) {
            var pool = pools.get(index1);
            if (pages.containsKey(pool.id())) result.addAll(pool.recipes());
        }
        return result;
    }

    /**
     * Pool membership and category bindings cannot change during a definition update.
     */
    public boolean acceptsPools(List<RecipePool> pools) {
        requireFrozen();
        if (pools.size() != registeredPools.size()) return false;
        for (int index4 = 0; index4 < pools.size(); index4++) {
            var pool = pools.get(index4);
            var previous = registeredPools.get(pool.id());
            if (previous == null || previous.hasExistingJeiPage() != pool.hasExistingJeiPage()
                || !Objects.equals(previous.existingJeiPageId(), pool.existingJeiPageId()))
                return false;
        }
        return true;
    }

    private void requireSamePools(List<RecipePool> pools) {
        if (!acceptsPools(pools))
            throw new IllegalArgumentException("JEI replacement changed the pool set or category bindings");
    }

    public List<RecipePoolJeiPage.PreparedUpdate> prepareUpdates(List<RecipePool> pools, RecipeJeiDisplays.Snapshot displays) {
        var updates = tryPrepareUpdates(pools, displays);
        if (updates == null) throw new IllegalStateException("A JEI page does not support definition updates");
        return updates;
    }

    /**
     * A supported shape with an unsupported page is an ordinary preparation rejection.
     */
    public @Nullable List<RecipePoolJeiPage.PreparedUpdate> tryPrepareUpdates(List<RecipePool> pools, RecipeJeiDisplays.Snapshot displays) {
        requireFrozen();
        if (!acceptsPools(pools)) return null;
        var updates = new ObjectArrayList<RecipePoolJeiPage.PreparedUpdate>();
        for (int index5 = 0; index5 < pools.size(); index5++) {
            var pool = pools.get(index5);
            if (externalPages.containsKey(pool.id())) continue;
            var page = pages.get(pool.id());
            var update = page.prepareUpdate(pool, displays);
            if (update == null) return null;
            updates.add(update);
        }
        return updates;
    }

    public List<ItemStack> catalysts(ResourceLocation poolId) {
        requireFrozen();
        if (!registeredPools.containsKey(poolId)) {
            throw new IllegalArgumentException("Unknown JEI recipe pool: " + poolId);
        }
        var entries = catalysts.get(poolId);
        return entries == null ? List.of() : entries;
    }

    private static Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool> indexPools(List<RecipePool> pools) {
        var known = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>(pools.size());
        for (int index = 0; index < pools.size(); index++) {
            var pool = pools.get(index);
            if (known.putIfAbsent(pool.id(), pool) != null)
                throw new IllegalArgumentException("Duplicate frozen recipe pool: " + pool.id());
        }
        return known;
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
