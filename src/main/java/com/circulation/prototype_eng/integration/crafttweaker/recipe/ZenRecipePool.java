package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.CompositeRecipePool;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ClientJeiDeclarations;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiOptions;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiPage;
import ae2.api.stacks.GenericStack;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;

/**
 * An immutable declaration submitted to the existing recipe registration phase.
 */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Pool")
public final class ZenRecipePool {
    private final RecipePool value;
    private final List<ZenRecipe> recipes;
    @Nullable
    private final ZenJeiOptions options;
    @Nullable
    private final ZenJeiPage page;

    private ZenRecipePool(RecipePool value, List<ZenRecipe> recipes) {
        this(value, recipes, null, null);
    }

    private ZenRecipePool(RecipePool value, List<ZenRecipe> recipes, @Nullable ZenJeiOptions options, @Nullable ZenJeiPage page) {
        this.value = value;
        this.recipes = recipes;
        this.options = options;
        this.page = page;
    }

    /**
     * Addons retain their actual pool lookup policy and exact recipe/codec handles.
     */
    public static ZenRecipePool of(RecipePool pool, ZenRecipe[] recipes) {
        var bindings = new ObjectArrayList<ZenRecipe>(recipes.length);
        if (pool.recipes().size() != recipes.length)
            throw new IllegalArgumentException("Pool recipe binding count differs");
        for (int i = 0; i < recipes.length; i++) {
            var recipe = recipes[i];
            if (pool.recipes().get(i) != recipe.recipe())
                throw new IllegalArgumentException("Pool must retain exact recipe bindings");
            bindings.add(recipe);
        }
        return new ZenRecipePool(pool, bindings);
    }

    @ZenMethod
    public static ZenRecipePool create(String id, String translationKey, ZenRecipe[] recipes) {
        var definitions = new ObjectArrayList<ZenRecipe>(recipes.length);
        var values = new ObjectArrayList<MachineRecipe>(recipes.length);
        for (var recipe : recipes) {
            definitions.add(recipe);
            values.add(recipe.recipe());
        }
        return new ZenRecipePool(new DefaultRecipePool(ZenRecipeSupport.id(id), translationKey, values),
            definitions);
    }

    @ZenMethod
    public static ZenRecipePool simple(String id, String translationKey, ZenRecipe[] recipes) {
        var entries = new ObjectArrayList<SimpleRecipePool.Entry>(recipes.length);
        for (var recipe : recipes) entries.add(SimpleRecipePool.Entry.of(recipe.recipe()));
        return of(new SimpleRecipePool(ZenRecipeSupport.id(id), translationKey, entries), recipes);
    }

    @ZenMethod
    public ZenRecipePool auxiliaryInputs(ZenResource[] inputs) {
        if (!(value instanceof SimpleRecipePool simple))
            throw new IllegalArgumentException("Shared auxiliary inputs require a simple recipe pool");
        var materials = new ObjectArrayList<GenericStack>(inputs.length);
        for (var input : inputs) materials.add(input.stack());
        var updated = simple.withAuxiliaryInputs(materials);
        if (updated == simple) return this;
        var bindings = new ObjectArrayList<ZenRecipe>(recipes.size());
        for (int i = 0; i < recipes.size(); i++)
            bindings.add(new ZenRecipe(recipes.get(i).codec().withRecipe(updated.recipes().get(i))));
        return new ZenRecipePool(updated, bindings, options, page);
    }

    /**
     * Combines the exact providers and recipe/codec handles; no child is registered implicitly.
     */
    @ZenMethod
    public static ZenRecipePool combine(String id, String translationKey, ZenRecipePool[] pools) {
        var providers = new ObjectArrayList<RecipePool>(pools.length);
        var handles = new Reference2ObjectOpenHashMap<MachineRecipe, ZenRecipe>();
        for (var pool : pools) {
            providers.add(pool.value);
            for (int index3 = 0; index3 < pool.recipes.size(); index3++) {
                var recipe = pool.recipes.get(index3);
                var previous = handles.putIfAbsent(recipe.recipe(), recipe);
                if (previous != null && (previous.codec() != recipe.codec() || !previous.revision().equals(recipe.revision())))
                    throw new IllegalArgumentException("Shared recipe must retain one exact codec and revision: " + recipe.id());
            }
        }
        var combined = new CompositeRecipePool(ZenRecipeSupport.id(id), translationKey, providers);
        var bindings = new ObjectArrayList<ZenRecipe>(combined.recipes().size());
        var elements = combined.recipes();
        for (int index = 0; index < elements.size(); index++) {
            var recipe = elements.get(index);
            bindings.add(handles.get(recipe));
        }
        return new ZenRecipePool(combined, bindings);
    }

    @ZenMethod
    public ZenRecipePool existingJeiPage(String categoryUid) {
        var target = switch (value) {
            case DefaultRecipePool pool -> pool.withExistingJeiPage(categoryUid);
            case SimpleRecipePool pool -> pool.withExistingJeiPage(categoryUid);
            default ->
                throw new IllegalArgumentException("Addon pools declare existing categories through their Java pool contract");
        };
        return new ZenRecipePool(target, recipes, options, page);
    }

    @ZenMethod
    public ZenRecipePool jeiOptions(ZenJeiOptions settings) {
        return new ZenRecipePool(value, recipes, settings, page);
    }

    @ZenMethod
    public ZenRecipePool customJeiPage(ZenJeiPage handle) {
        return new ZenRecipePool(value, recipes, options, handle);
    }

    public void declareClient() {
        ClientJeiDeclarations.declare(value.id(), options, page);
    }

    @ZenMethod
    public void register() {
        declareClient();
        DefinitionRegistrations.recipes(event -> {
            event.registry().register(value);
            for (int index2 = 0; index2 < recipes.size(); index2++) {
                var recipe = recipes.get(index2);
                event.registry().declareRevision(recipe.recipe(), recipe.revision());
            }
        });
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

    public RecipePool value() {
        return value;
    }

    public List<ZenRecipe> recipes() {
        return recipes;
    }
}
