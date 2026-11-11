package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;
import java.util.List;

/**
 * An author's declaration table. Same-name definitions affect subsequent lookups only; consumers
 * capture concrete handles, never this table. Freeze closes declarations without changing any handle.
 */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Bindings")
public final class ZenRecipeBindings {
    private static final Object2ObjectOpenHashMap<ResourceLocation, ZenRecipeBindings> SCOPES = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<ResourceLocation, ZenMatcher> matchers = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<ResourceLocation, ZenSelectiveModifier> selective = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<ResourceLocation, ZenOverlayModifier> overlays = new Object2ObjectOpenHashMap<>();
    private final RecipeProcessCodecs resolver = new RecipeProcessCodecs(List.of());
    private boolean frozen;
    private RecipeDeclarationKey manifest;

    @ZenMethod
    public static ZenRecipeBindings create() {
        return new ZenRecipeBindings();
    }

    /** Shared declaration scope across preinit and ordinary loaders, without sharing script globals. */
    @ZenMethod
    public static synchronized ZenRecipeBindings scope(String id) {
        return SCOPES.computeIfAbsent(ZenRecipeSupport.id(id), _ -> new ZenRecipeBindings());
    }

    private void requireDeclarations() {
        if (frozen) throw new IllegalStateException("Recipe name declarations are frozen");
    }

    @ZenMethod
    public synchronized void defineMatcher(ZenMatcher matcher) {
        requireDeclarations();
        matchers.put(Objects.requireNonNull(matcher).value().id(), matcher);
    }

    @ZenMethod
    public synchronized ZenMatcher matcher(String id) {
        var matcher = matchers.get(ZenRecipeSupport.id(id));
        if (matcher == null) throw new IllegalArgumentException("Unknown matcher: " + id);
        return matcher;
    }

    @ZenMethod
    public synchronized void defineSelective(ZenSelectiveModifier modifier) {
        requireDeclarations();
        selective.put(Objects.requireNonNull(modifier).value().id(), modifier);
    }

    @ZenMethod
    public synchronized ZenSelectiveModifier selective(String id) {
        var modifier = selective.get(ZenRecipeSupport.id(id));
        if (modifier == null) throw new IllegalArgumentException("Unknown selective modifier: " + id);
        return modifier;
    }

    @ZenMethod
    public synchronized void defineOverlay(ZenOverlayModifier modifier) {
        requireDeclarations();
        overlays.put(Objects.requireNonNull(modifier).value().id(), modifier);
    }

    @ZenMethod
    public synchronized ZenOverlayModifier overlay(String id) {
        var modifier = overlays.get(ZenRecipeSupport.id(id));
        if (modifier == null) throw new IllegalArgumentException("Unknown overlay modifier: " + id);
        return modifier;
    }

    @ZenMethod
    public synchronized void removeMatcher(String id) {
        requireDeclarations();
        matchers.remove(ZenRecipeSupport.id(id));
    }

    @ZenMethod
    public synchronized void removeSelective(String id) {
        requireDeclarations();
        selective.remove(ZenRecipeSupport.id(id));
    }

    @ZenMethod
    public synchronized void removeOverlay(String id) {
        requireDeclarations();
        overlays.remove(ZenRecipeSupport.id(id));
    }

    @ZenMethod
    public synchronized void clear() {
        requireDeclarations();
        matchers.clear();
        selective.clear();
        overlays.clear();
    }

    @ZenMethod
    public synchronized void freeze() {
        frozen = true;
    }

    /**
     * One explicit following directory, including cold-start history. Machine declarations can
     * retain resolver() before this call; the same resolver is bound during the recipe event.
     */
    @ZenMethod
    public synchronized void registerPools(ZenRecipePool[] pools, ZenRecipe[] historical) {
        requireDeclarations();
        var declarations = new ObjectArrayList<ZenRecipePool>(pools.length);
        var current = new ObjectArrayList<ZenRecipe>();
        var history = new ObjectArrayList<ZenRecipe>(historical.length);
        var codecs = new ObjectArrayList<RecipeProcessNbt>();
        for (var pool : pools) {
            declarations.add(Objects.requireNonNull(pool));
            current.addAll(pool.recipes());
        }
        for (var recipe : current) codecs.add(recipe.codec());
        for (var recipe : historical) {
            history.add(Objects.requireNonNull(recipe));
            codecs.add(recipe.codec());
        }
        // Validate all version/identity conflicts before registering a callback or mutating names.
        var prepared = resolver.prepare(resolver.snapshot(), codecs);
        var frozenPools = ObjectLists.unmodifiable(declarations);
        var frozenCurrent = ObjectLists.unmodifiable(current);
        var frozenHistory = ObjectLists.unmodifiable(history);
        resolver.install(prepared);
        DefinitionRegistrations.recipes(event -> {
            for (var pool : frozenPools) event.registry().register(pool.value());
            for (var recipe : frozenCurrent) event.registry().declareRevision(recipe.recipe(), recipe.revision());
            for (var recipe : frozenHistory) event.registry().declareHistoricalRevision(recipe.recipe(), recipe.revision());
            event.registry().bindProcessCodecs(resolver);
        });
        frozen = true;
    }

    public RecipeProcessCodecs resolver() {
        return resolver;
    }

    /** The author asserts one identity for the complete local directory, including other scopes. */
    @ZenMethod
    public synchronized void declareManifest(String setId, String version) {
        var key = new RecipeDeclarationKey(ZenRecipeSupport.id(setId), version);
        if (manifest != null) {
            if (!manifest.equals(key)) throw new IllegalStateException("This scope already declared a complete manifest identity");
            return;
        }
        DefinitionRegistrations.declarations(event -> event.declarations().register(key, event.snapshot()));
        manifest = key;
    }
}
