package com.circulation.prototype_eng.integration.recipeimport.tconstruct;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipe;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import slimeknights.tconstruct.library.TinkerRegistry;
import slimeknights.tconstruct.library.smeltery.AlloyRecipe;

import java.util.List;

public final class TConstructAlloySource {
    private TConstructAlloySource() {
    }

    public static List<ImportedRecipe<AlloyContext>> read(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("tconstruct");
        var sourceRecipes = TinkerRegistry.getAlloys();
        var result = new ObjectArrayList<ImportedRecipe<AlloyContext>>(sourceRecipes.size());
        for (int index = 0; index < sourceRecipes.size(); index++) {
            var source = sourceRecipes.get(index);
            try {
                result.add(convert(RecipeImportSupport.id(poolId, "alloy/" + index), source));
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected TConstruct alloy recipe {} in pool {}", index, poolId, exception);
            }
        }
        return result;
    }

    public static ImportedRecipe<AlloyContext> convert(ResourceLocation id, AlloyRecipe source) {
        if (!source.isValid()) throw new IllegalArgumentException("Invalid TConstruct alloy source");
        var fluids = source.getFluids();
        var inputs = new ObjectArrayList<GenericStack>(fluids.size());
        for (int inputIndex = 0; inputIndex < fluids.size(); inputIndex++)
            inputs.add(RecipeImportSupport.fluid(fluids.get(inputIndex)));
        var recipe = new RecipeBuilder(id, 4);
        RecipeImportSupport.resources(recipe, inputs, List.of(RecipeImportSupport.fluid(source.getResult())), List.of());
        return new ImportedRecipe<>(recipe, new AlloyContext(source));
    }

    public record AlloyContext(AlloyRecipe source) {
    }
}
