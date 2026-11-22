package com.circulation.prototype_eng.api.recipe.importing.ic2;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.ic2.Ic2CannerSources;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CannerEnrichRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, NBTTagCompound metadata);
    }

    @SuppressWarnings("unused")
    public CannerEnrichRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public CannerEnrichRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> Ic2CannerSources.readEnrich(id, onCreate), RecipePoolType.GENERAL);
    }

    private CannerEnrichRecipePool(CannerEnrichRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public CannerEnrichRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new CannerEnrichRecipePool(this, recipes, machines);
    }
}
