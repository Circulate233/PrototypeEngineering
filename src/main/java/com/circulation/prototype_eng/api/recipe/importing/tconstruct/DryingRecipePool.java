package com.circulation.prototype_eng.api.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.tconstruct.TConstructDryingSource;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class DryingRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe);
    }

    @SuppressWarnings("unused")
    public DryingRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public DryingRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> TConstructDryingSource.read(id, onCreate), RecipePoolType.SINGLE_INPUT);
    }

    private DryingRecipePool(DryingRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public DryingRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new DryingRecipePool(this, recipes, machines);
    }
}
