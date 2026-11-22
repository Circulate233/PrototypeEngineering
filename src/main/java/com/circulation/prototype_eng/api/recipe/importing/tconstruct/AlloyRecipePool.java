package com.circulation.prototype_eng.api.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.tconstruct.TConstructAlloySource;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class AlloyRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe);
    }

    @SuppressWarnings("unused")
    public AlloyRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public AlloyRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> TConstructAlloySource.read(id, onCreate), RecipePoolType.GENERAL);
    }

    private AlloyRecipePool(AlloyRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public AlloyRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new AlloyRecipePool(this, recipes, machines);
    }
}
