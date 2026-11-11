package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ClientJeiDeclarations;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiOptions;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiPage;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Objects;

/** An immutable declaration submitted to the existing recipe registration phase. */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Pool")
public final class ZenRecipePool {
    private final RecipePool value;
    private final List<ZenRecipe> recipes;
    @Nullable private final ZenJeiOptions options;
    @Nullable private final ZenJeiPage page;

    private ZenRecipePool(RecipePool value, List<ZenRecipe> recipes) {
        this(value, recipes, null, null);
    }

    private ZenRecipePool(RecipePool value, List<ZenRecipe> recipes, @Nullable ZenJeiOptions options, @Nullable ZenJeiPage page) {
        this.value = value;
        this.recipes = recipes;
        this.options = options;
        this.page = page;
    }

    /** Addons retain their actual pool lookup policy and exact recipe/codec handles. */
    public static ZenRecipePool of(RecipePool pool, ZenRecipe[] recipes) {
        Objects.requireNonNull(pool);
        var bindings = new ObjectArrayList<ZenRecipe>(recipes.length);
        if (pool.recipes().size() != recipes.length) throw new IllegalArgumentException("Pool recipe binding count differs");
        for (int i = 0; i < recipes.length; i++) {
            var recipe = Objects.requireNonNull(recipes[i]);
            if (pool.recipes().get(i) != recipe.recipe()) throw new IllegalArgumentException("Pool must retain exact recipe bindings");
            bindings.add(recipe);
        }
        return new ZenRecipePool(pool, ObjectLists.unmodifiable(bindings));
    }

    @ZenMethod
    public static ZenRecipePool create(String id, String translationKey, ZenRecipe[] recipes) {
        var definitions = new ObjectArrayList<ZenRecipe>(recipes.length);
        var values = new ObjectArrayList<MachineRecipe>(recipes.length);
        for (var recipe : recipes) {
            definitions.add(Objects.requireNonNull(recipe));
            values.add(recipe.recipe());
        }
        return new ZenRecipePool(new DefaultRecipePool(ZenRecipeSupport.id(id), translationKey, values),
            ObjectLists.unmodifiable(definitions));
    }

    @ZenMethod
    public ZenRecipePool existingJeiPage(String categoryUid) {
        Objects.requireNonNull(categoryUid);
        var target = switch (value) {
            case DefaultRecipePool pool -> pool.withExistingJeiPage(categoryUid);
            case SimpleRecipePool pool -> pool.withExistingJeiPage(categoryUid);
            default -> throw new IllegalArgumentException("Addon pools declare existing categories through their Java pool contract");
        };
        return new ZenRecipePool(target, recipes, options, page);
    }

    @ZenMethod
    public static ZenRecipePool simple(String id, String translationKey, ZenRecipe[] recipes) {
        var entries = new ObjectArrayList<SimpleRecipePool.Entry>(recipes.length);
        for (var recipe : recipes) entries.add(SimpleRecipePool.Entry.of(Objects.requireNonNull(recipe).recipe()));
        return of(new SimpleRecipePool(ZenRecipeSupport.id(id), translationKey, entries), recipes);
    }

    @ZenMethod public ZenRecipePool jeiOptions(ZenJeiOptions settings) {
        return new ZenRecipePool(value, recipes, Objects.requireNonNull(settings), page);
    }

    @ZenMethod public ZenRecipePool customJeiPage(ZenJeiPage handle) {
        return new ZenRecipePool(value, recipes, options, Objects.requireNonNull(handle));
    }

    public void declareClient() { ClientJeiDeclarations.declare(value.id(), options, page); }

    @ZenMethod
    public void register() {
        declareClient();
        DefinitionRegistrations.recipes(event -> {
            event.registry().register(value);
            for (var recipe : recipes) event.registry().declareRevision(recipe.recipe(), recipe.revision());
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
