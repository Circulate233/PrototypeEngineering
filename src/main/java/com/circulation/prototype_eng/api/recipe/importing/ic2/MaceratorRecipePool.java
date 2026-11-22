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

public final class MaceratorRecipePool extends ImportedRecipePool<Ic2RecipeContext> {
    public MaceratorRecipePool(ResourceLocation id, String title) {
        this(id, title, (_, _) -> true);
    }

    public MaceratorRecipePool(ResourceLocation id, String title,
        BiPredicate<RecipeBuilder, Ic2RecipeContext> onCreate) {
        super(id, title, () -> {
            RecipeImportSupport.requireMod("ic2");
            return Ic2RecipeSources.readMacerator(id);
        }, onCreate);
    }

    private MaceratorRecipePool(MaceratorRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public MaceratorRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new MaceratorRecipePool(this, recipes, machines);
    }
}
