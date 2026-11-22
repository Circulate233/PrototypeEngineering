package com.circulation.prototype_eng.api.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.thaumcraft.ThaumcraftCrucibleSource;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import thaumcraft.api.aspects.AspectList;

import java.util.List;

public final class CrucibleRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, ResourceLocation sourceId, AspectList aspects, String research);
    }

    @SuppressWarnings("unused")
    public CrucibleRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public CrucibleRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> ThaumcraftCrucibleSource.read(id, onCreate), RecipePoolType.SINGLE_INPUT);
    }

    private CrucibleRecipePool(CrucibleRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public CrucibleRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new CrucibleRecipePool(this, recipes, machines);
    }
}
