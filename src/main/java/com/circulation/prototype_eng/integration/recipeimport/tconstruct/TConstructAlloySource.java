package com.circulation.prototype_eng.integration.recipeimport.tconstruct;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.importing.tconstruct.AlloyRecipePool;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import slimeknights.tconstruct.library.TinkerRegistry;
import slimeknights.tconstruct.library.smeltery.AlloyRecipe;

import java.util.List;

public final class TConstructAlloySource {
    private TConstructAlloySource() {
    }

    public static List<MachineRecipe> read(ResourceLocation poolId) {
        return read(poolId, null);
    }

    public static List<MachineRecipe> read(ResourceLocation poolId, @Nullable AlloyRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("tconstruct");
        var sourceRecipes = TinkerRegistry.getAlloys();
        return convertAll(poolId, sourceRecipes, onCreate);
    }

    static List<MachineRecipe> convertAll(ResourceLocation poolId, List<AlloyRecipe> sourceRecipes,
                                          @Nullable AlloyRecipePool.OnCreate onCreate) {
        var result = new ObjectArrayList<MachineRecipe>(sourceRecipes.size());
        for (int index = 0; index < sourceRecipes.size(); index++) {
            var source = sourceRecipes.get(index);
            RecipeBuilder recipe;
            try {
                recipe = draft(RecipeImportSupport.id(poolId, "alloy/" + index), source);
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected TConstruct alloy recipe {} in pool {}", index, poolId, exception);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe)) result.add(recipe.build());
        }
        return result;
    }

    public static MachineRecipe convert(ResourceLocation id, AlloyRecipe source) {
        return draft(id, source).build();
    }

    private static RecipeBuilder draft(ResourceLocation id, AlloyRecipe source) {
        if (!source.isValid()) throw new IllegalArgumentException("Invalid TConstruct alloy source");
        var fluids = source.getFluids();
        var inputs = new ObjectArrayList<GenericStack>(fluids.size());
        for (int inputIndex = 0; inputIndex < fluids.size(); inputIndex++)
            inputs.add(RecipeImportSupport.fluid(fluids.get(inputIndex)));
        var recipe = new RecipeBuilder(id, 4);
        RecipeImportSupport.resources(recipe, inputs, List.of(RecipeImportSupport.fluid(source.getResult())), List.of());
        return recipe;
    }

}
