package com.circulation.prototype_eng.api.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import com.circulation.prototype_eng.integration.recipeimport.thermalexpansion.ThermalInsolatorSource;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.function.BiPredicate;

public final class InsolatorTreeRecipePool extends ImportedRecipePool<ThermalInsolatorSource.InsolatorContext> {
    public InsolatorTreeRecipePool(ResourceLocation id, String title) {
        this(id, title, (_, _) -> true);
    }

    public InsolatorTreeRecipePool(ResourceLocation id, String title,
        BiPredicate<RecipeBuilder, ThermalInsolatorSource.InsolatorContext> onCreate) {
        super(id, title, () -> {
            RecipeImportSupport.requireMod("thermalexpansion");
            return ThermalInsolatorSource.read(id, true);
        }, onCreate);
    }

    private InsolatorTreeRecipePool(InsolatorTreeRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public InsolatorTreeRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new InsolatorTreeRecipePool(this, recipes, machines);
    }
}
