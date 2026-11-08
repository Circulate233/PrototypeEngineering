package com.circulation.prototype_eng.api.recipe;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;
import com.circulation.prototype_eng.api.machine.MachineType;

/** Server-safe pool definition. Client JEI pages bind to its id separately. */
public final class RecipePool {
    private final ResourceLocation id;
    private final String translationKey;
    private final List<MachineRecipe> recipes;
    private final List<MachineType> machines;

    public RecipePool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes) {
        this(id, translationKey, recipes, List.of());
    }

    // Only RecipeRegistry derives static reverse bindings from the machine declarations it freezes.
    RecipePool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes, List<MachineType> machines) {
        Objects.requireNonNull(id, "id");
        if (translationKey == null || translationKey.isBlank()) { throw new IllegalArgumentException("Pool title is required"); }
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        var copy = new ObjectArrayList<MachineRecipe>(recipes.size());
        for (MachineRecipe recipe : recipes) {
            Objects.requireNonNull(recipe, "recipe");
            if (!ids.add(recipe.id())) { throw new IllegalArgumentException("Duplicate recipe in pool " + id + ": " + recipe.id()); }
            copy.add(recipe);
        }
        this.id = id;
        this.translationKey = translationKey;
        this.recipes = ObjectLists.unmodifiable(copy);
        this.machines = ObjectLists.unmodifiable(new ObjectArrayList<>(machines));
    }

    public ResourceLocation id() { return id; }
    public String translationKey() { return translationKey; }
    public List<MachineRecipe> recipes() { return recipes; }

    /** Registered canonical machine declarations, not all possible dynamic instance selections. */
    public List<MachineType> machines() { return machines; }
}
