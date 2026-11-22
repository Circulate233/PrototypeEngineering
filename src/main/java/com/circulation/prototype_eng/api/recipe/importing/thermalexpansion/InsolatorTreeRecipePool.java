package com.circulation.prototype_eng.api.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.thermalexpansion.ThermalInsolatorSource;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class InsolatorTreeRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, int energy, int water, int secondaryChance, boolean hasFertilizer);
    }

    @SuppressWarnings("unused")
    public InsolatorTreeRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public InsolatorTreeRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> ThermalInsolatorSource.readTree(id, onCreate), RecipePoolType.GENERAL);
    }

    private InsolatorTreeRecipePool(InsolatorTreeRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public InsolatorTreeRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new InsolatorTreeRecipePool(this, recipes, machines);
    }
}
