package com.circulation.prototype_eng.integration.recipeimport.tconstruct;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.tconstruct.DryingRecipePool;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.DryingRecipe;
import slimeknights.tconstruct.library.TinkerRegistry;

import java.util.List;

public final class TConstructDryingSource {
    private TConstructDryingSource() {
    }

    public static List<MachineRecipe> read(ResourceLocation poolId, @Nullable DryingRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("tconstruct");
        return convertAll(poolId, TinkerRegistry.getAllDryingRecipes(), onCreate);
    }

    static List<MachineRecipe> convertAll(ResourceLocation poolId, List<DryingRecipe> sources, @Nullable DryingRecipePool.OnCreate onCreate) {
        var result = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < sources.size(); index++) {
            RecipeBuilder recipe;
            try {
                recipe = draft(RecipeImportSupport.id(poolId, "drying/" + index), sources.get(index));
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected TConstruct drying recipe {} in pool {}", index, poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe)) result.add(recipe.build());
        }
        return result;
    }

    private static RecipeBuilder draft(ResourceLocation id, DryingRecipe source) {
        var candidates = new ObjectArrayList<AEKey>();
        for (var stack : source.input.getInputs()) {
            var key = AEItemKey.of(stack);
            if (key != null && !candidates.contains(key)) candidates.add(key);
        }
        if (candidates.isEmpty() || source.input.amountNeeded <= 0)
            throw new IllegalArgumentException("TConstruct drying input has no valid candidate or amount");
        int amount = source.input.amountNeeded;
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, "input"),
            (candidate, _) -> candidate instanceof AEItemKey item && source.matches(item.toStack(amount)));
        var dynamic = new DynamicInputGroup(matcher, candidates.getFirst(), amount, candidates, Integer.MAX_VALUE);
        var recipe = new RecipeBuilder(id, source.getTime());
        var resources = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(RecipeImportSupport.item(source.getResult())),
            false, ResourceChance.ALWAYS, null, List.of(), List.of(dynamic));
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.requirementId(recipe, "resources"), resources));
        return recipe;
    }
}
