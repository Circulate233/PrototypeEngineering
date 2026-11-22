package com.circulation.prototype_eng.api.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.thermalexpansion.ThermalMachineSources;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class EnchanterRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, int energy, int experience, String enchantName);
    }

    @SuppressWarnings("unused")
    public EnchanterRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public EnchanterRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> ThermalMachineSources.readEnchanter(id, onCreate), RecipePoolType.GENERAL);
    }

    private EnchanterRecipePool(EnchanterRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public EnchanterRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new EnchanterRecipePool(this, recipes, machines);
    }
}
