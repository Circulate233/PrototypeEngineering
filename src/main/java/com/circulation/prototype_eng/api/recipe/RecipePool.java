package com.circulation.prototype_eng.api.recipe;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Server-safe pool definition. Client JEI pages bind to its id separately. */
public record RecipePool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes) {
    public RecipePool {
        Objects.requireNonNull(id, "id");
        if (translationKey == null || translationKey.isBlank()) { throw new IllegalArgumentException("Pool title is required"); }
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        var copy = new ObjectArrayList<MachineRecipe>(recipes.size());
        for (MachineRecipe recipe : recipes) {
            Objects.requireNonNull(recipe, "recipe");
            if (!ids.add(recipe.id())) { throw new IllegalArgumentException("Duplicate recipe in pool " + id + ": " + recipe.id()); }
            copy.add(recipe);
        }
        recipes = ObjectLists.unmodifiable(copy);
    }
}
