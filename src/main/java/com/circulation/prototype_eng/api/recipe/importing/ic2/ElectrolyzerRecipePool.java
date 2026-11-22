package com.circulation.prototype_eng.api.recipe.importing.ic2;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.ic2.Ic2FluidRecipeSources;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ElectrolyzerRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, int energyPerTickEu, EnumFacing[] outputDirections);
    }

    @SuppressWarnings("unused")
    public ElectrolyzerRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public ElectrolyzerRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> Ic2FluidRecipeSources.readElectrolyzer(id, onCreate), RecipePoolType.SINGLE_INPUT);
    }

    private ElectrolyzerRecipePool(ElectrolyzerRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public ElectrolyzerRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new ElectrolyzerRecipePool(this, recipes, machines);
    }
}
