package com.circulation.prototype_eng.api.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.nuclearcraft.NuclearCraftRecipeSources;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class AssemblerRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, double durationTicks, double energyPerTick, double radiation, List<Object> extras);
    }

    @SuppressWarnings("unused")
    public AssemblerRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public AssemblerRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> NuclearCraftRecipeSources.readAssembler(id, onCreate), RecipePoolType.GENERAL);
    }

    private AssemblerRecipePool(AssemblerRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public AssemblerRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new AssemblerRecipePool(this, recipes, machines);
    }
}
