package com.circulation.prototype_eng.api.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.integration.recipeimport.tconstruct.TConstructCastingSources;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class TableCastingRecipePool extends ImportedRecipePool {
    @FunctionalInterface
    public interface OnCreate {
        boolean create(RecipeBuilder recipe, boolean consumesCast, boolean switchOutputs);
    }

    @SuppressWarnings("unused")
    public TableCastingRecipePool(ResourceLocation id, String title) {
        this(id, title, null);
    }

    public TableCastingRecipePool(ResourceLocation id, String title, @Nullable OnCreate onCreate) {
        super(id, title, () -> TConstructCastingSources.readTable(id, onCreate), RecipePoolType.GENERAL);
    }

    private TableCastingRecipePool(TableCastingRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public TableCastingRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new TableCastingRecipePool(this, recipes, machines);
    }
}
