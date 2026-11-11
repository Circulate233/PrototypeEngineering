package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
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
    private final DefaultRecipePool value;
    private final List<ZenRecipe> recipes;

    private ZenRecipePool(DefaultRecipePool value, List<ZenRecipe> recipes) {
        this.value = value;
        this.recipes = recipes;
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
        return new ZenRecipePool(value.withExistingJeiPage(Objects.requireNonNull(categoryUid)), recipes);
    }

    @ZenMethod
    public void register() {
        DefinitionRegistrations.recipes(event -> {
            event.registry().register(value);
            for (var recipe : recipes) event.registry().declareRevision(recipe.recipe(), recipe.revision());
        });
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

    public DefaultRecipePool value() {
        return value;
    }

    public List<ZenRecipe> recipes() {
        return recipes;
    }
}
