package com.circulation.prototype_eng.api.recipe.importing;

import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import net.minecraft.util.ResourceLocation;

import java.util.List;

public enum RecipePoolType {
    SINGLE_INPUT {
        @Override
        public RecipePool create(ResourceLocation id, String translationKey, List<MachineRecipe> recipes) {
            return SimpleRecipePool.forRecipes(id, translationKey, recipes);
        }
    },
    GENERAL {
        @Override
        public RecipePool create(ResourceLocation id, String translationKey, List<MachineRecipe> recipes) {
            return new DefaultRecipePool(id, translationKey, recipes);
        }
    };

    public abstract RecipePool create(ResourceLocation id, String translationKey, List<MachineRecipe> recipes);
}
