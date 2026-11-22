package com.circulation.prototype_eng.api.recipe.importing;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

public final class RecipePoolType {
    private static final Object2ObjectOpenHashMap<ResourceLocation, RecipePoolType> TYPES = new Object2ObjectOpenHashMap<>();
    public static final RecipePoolType SINGLE_INPUT = register(new ResourceLocation(Tags.MOD_ID, "single_input"),
        SimpleRecipePool::forRecipes);
    public static final RecipePoolType GENERAL = register(new ResourceLocation(Tags.MOD_ID, "general"), DefaultRecipePool::new);

    private final ResourceLocation id;
    private final Factory factory;

    @FunctionalInterface
    public interface Factory {
        RecipePool create(ResourceLocation id, String translationKey, List<MachineRecipe> recipes);
    }

    private RecipePoolType(ResourceLocation id, Factory factory) {
        this.id = id;
        this.factory = factory;
    }

    public static synchronized RecipePoolType register(ResourceLocation id, Factory factory) {
        Objects.requireNonNull(id, "Recipe pool type id");
        Objects.requireNonNull(factory, "Recipe pool factory");
        var type = new RecipePoolType(id, factory);
        if (TYPES.putIfAbsent(id, type) != null)
            throw new IllegalArgumentException("Duplicate recipe pool type: " + id);
        return type;
    }

    public static synchronized RecipePoolType require(ResourceLocation id) {
        Objects.requireNonNull(id, "Recipe pool type id");
        var type = TYPES.get(id);
        if (type == null) throw new IllegalArgumentException("Unknown recipe pool type: " + id);
        return type;
    }

    public static RecipePoolType require(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Recipe pool type id is required");
        return require(id.indexOf(':') < 0 ? new ResourceLocation(Tags.MOD_ID, id) : new ResourceLocation(id));
    }

    public ResourceLocation id() {
        return id;
    }

    public RecipePool create(ResourceLocation poolId, String translationKey, List<MachineRecipe> recipes) {
        var pool = Objects.requireNonNull(factory.create(poolId, translationKey, recipes), "Recipe pool factory returned null: " + id);
        if (!poolId.equals(pool.id()) || !translationKey.equals(pool.translationKey()))
            throw new IllegalArgumentException("Recipe pool factory changed pool identity or title: " + id);
        var actual = pool.recipes();
        if (actual.size() != recipes.size())
            throw new IllegalArgumentException("Recipe pool factory changed recipe membership: " + id);
        for (int index = 0; index < recipes.size(); index++) {
            if (actual.get(index) != recipes.get(index))
                throw new IllegalArgumentException("Recipe pool factory changed recipe identity or order: " + id);
        }
        return pool;
    }
}
