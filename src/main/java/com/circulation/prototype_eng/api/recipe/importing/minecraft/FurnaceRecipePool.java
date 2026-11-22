package com.circulation.prototype_eng.api.recipe.importing.minecraft;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.integration.recipeimport.minecraft.FurnaceRecipeSource;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.function.BiPredicate;

public final class FurnaceRecipePool extends ImportedRecipePool<FurnaceRecipeSource.FurnaceContext> {
    public FurnaceRecipePool(ResourceLocation id, String title) {
        this(id, title, (_, _) -> true);
    }

    public FurnaceRecipePool(ResourceLocation id, String title,
        BiPredicate<RecipeBuilder, FurnaceRecipeSource.FurnaceContext> onCreate) {
        super(id, title, () -> FurnaceRecipeSource.read(id), onCreate);
    }

    private FurnaceRecipePool(FurnaceRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public FurnaceRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new FurnaceRecipePool(this, recipes, machines);
    }
}
