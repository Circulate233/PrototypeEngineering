package com.circulation.prototype_eng.api.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.tconstruct.TConstructMeltingSource;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class MeltingRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, int temperature, int usableTemperature);
    }

    @SuppressWarnings("unused")
    public MeltingRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public MeltingRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> TConstructMeltingSource.read(id, onCreate), RecipePoolType.SINGLE_INPUT);
    }

    private MeltingRecipePool(MeltingRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public MeltingRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new MeltingRecipePool(this, recipes, machines);
    }
}
