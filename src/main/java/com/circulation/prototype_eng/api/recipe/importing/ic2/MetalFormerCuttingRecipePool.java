package com.circulation.prototype_eng.api.recipe.importing.ic2;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.ic2.Ic2RecipeSources;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class MetalFormerCuttingRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, NBTTagCompound metadata);
    }

    @SuppressWarnings("unused")
    public MetalFormerCuttingRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public MetalFormerCuttingRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> Ic2RecipeSources.readMetalFormerCutting(id, onCreate), RecipePoolType.SINGLE_INPUT);
    }

    private MetalFormerCuttingRecipePool(MetalFormerCuttingRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public MetalFormerCuttingRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new MetalFormerCuttingRecipePool(this, recipes, machines);
    }
}
