package com.circulation.prototype_eng.api.recipe.importing.ic2;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import com.circulation.prototype_eng.integration.recipeimport.ic2.Ic2RecipeContext;
import com.circulation.prototype_eng.integration.recipeimport.ic2.Ic2RecipeSources;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.function.BiPredicate;

public final class CompressorRecipePool extends ImportedRecipePool<Ic2RecipeContext> {
    public CompressorRecipePool(ResourceLocation id, String title) {
        this(id, title, (_, _) -> true);
    }

    public CompressorRecipePool(ResourceLocation id, String title,
        BiPredicate<RecipeBuilder, Ic2RecipeContext> onCreate) {
        super(id, title, () -> {
            RecipeImportSupport.requireMod("ic2");
            return Ic2RecipeSources.readCompressor(id);
        }, onCreate);
    }

    private CompressorRecipePool(CompressorRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public CompressorRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new CompressorRecipePool(this, recipes, machines);
    }
}
