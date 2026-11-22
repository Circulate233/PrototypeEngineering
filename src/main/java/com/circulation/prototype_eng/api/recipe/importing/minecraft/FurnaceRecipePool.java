package com.circulation.prototype_eng.api.recipe.importing.minecraft;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.minecraft.FurnaceRecipeSource;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class FurnaceRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, float experience);
    }

    @SuppressWarnings("unused")
    public FurnaceRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public FurnaceRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> FurnaceRecipeSource.read(id, onCreate), RecipePoolType.SINGLE_INPUT);
    }

    private FurnaceRecipePool(FurnaceRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public FurnaceRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new FurnaceRecipePool(this, recipes, machines);
    }
}
