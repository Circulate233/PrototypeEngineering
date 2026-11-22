package com.circulation.prototype_eng.api.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import com.circulation.prototype_eng.integration.recipeimport.tconstruct.TConstructAlloySource;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class AlloyRecipePool extends ImportedRecipePool<TConstructAlloySource.AlloyContext> {
    public AlloyRecipePool(ResourceLocation id, String title) {
        this(id, title, _ -> true);
    }

    public AlloyRecipePool(ResourceLocation id, String title,
        Predicate<RecipeBuilder> onCreate) {
        super(id, title, () -> {
            RecipeImportSupport.requireMod("tconstruct");
            return TConstructAlloySource.read(id);
        }, adapt(onCreate));
    }

    private static BiPredicate<RecipeBuilder, TConstructAlloySource.AlloyContext> adapt(Predicate<RecipeBuilder> callback) {
        Objects.requireNonNull(callback, "onCreate");
        return (recipe, _) -> callback.test(recipe);
    }

    private AlloyRecipePool(AlloyRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public AlloyRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new AlloyRecipePool(this, recipes, machines);
    }
}
