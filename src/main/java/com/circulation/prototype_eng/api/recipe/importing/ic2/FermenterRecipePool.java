package com.circulation.prototype_eng.api.recipe.importing.ic2;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.ic2.Ic2FluidRecipeSources;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class FermenterRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, int heat, int biomassPerFertilizer, ItemStack fertilizer);
    }

    @SuppressWarnings("unused")
    public FermenterRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public FermenterRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> Ic2FluidRecipeSources.readFermenter(id, onCreate), RecipePoolType.SINGLE_INPUT);
    }

    private FermenterRecipePool(FermenterRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public FermenterRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new FermenterRecipePool(this, recipes, machines);
    }
}
