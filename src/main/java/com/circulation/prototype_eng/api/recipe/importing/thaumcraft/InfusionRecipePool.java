package com.circulation.prototype_eng.api.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.thaumcraft.ThaumcraftInfusionSource;
import net.minecraft.item.ItemStack;
import thaumcraft.api.aspects.AspectList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class InfusionRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, ResourceLocation sourceId, AspectList aspects, int instability, String research, ItemStack mainInput);
    }

    @SuppressWarnings("unused")
    public InfusionRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public InfusionRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> ThaumcraftInfusionSource.read(id, onCreate), RecipePoolType.GENERAL);
    }

    private InfusionRecipePool(InfusionRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public InfusionRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new InfusionRecipePool(this, recipes, machines);
    }
}
