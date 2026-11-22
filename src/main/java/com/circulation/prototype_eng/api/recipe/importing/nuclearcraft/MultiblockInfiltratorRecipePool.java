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

public final class MultiblockInfiltratorRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, double durationTicks, double energyPerTick, double radiation, List<Object> extras, double heatingFactor);
    }

    @SuppressWarnings("unused")
    public MultiblockInfiltratorRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public MultiblockInfiltratorRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> NuclearCraftRecipeSources.readMultiblockInfiltrator(id, onCreate), RecipePoolType.GENERAL);
    }

    private MultiblockInfiltratorRecipePool(MultiblockInfiltratorRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public MultiblockInfiltratorRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new MultiblockInfiltratorRecipePool(this, recipes, machines);
    }
}
