package com.circulation.prototype_eng.api.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import com.circulation.prototype_eng.integration.recipeimport.tconstruct.TConstructMeltingSource;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.function.BiPredicate;

public final class MeltingRecipePool extends ImportedRecipePool<TConstructMeltingSource.MeltingContext> {
    public MeltingRecipePool(ResourceLocation id, String title) {
        this(id, title, (_, _) -> true);
    }

    public MeltingRecipePool(ResourceLocation id, String title,
        BiPredicate<RecipeBuilder, TConstructMeltingSource.MeltingContext> onCreate) {
        super(id, title, () -> {
            RecipeImportSupport.requireMod("tconstruct");
            return TConstructMeltingSource.read(id);
        }, onCreate);
    }

    private MeltingRecipePool(MeltingRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public MeltingRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new MeltingRecipePool(this, recipes, machines);
    }
}
