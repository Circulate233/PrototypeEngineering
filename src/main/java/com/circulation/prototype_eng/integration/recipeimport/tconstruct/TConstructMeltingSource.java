package com.circulation.prototype_eng.integration.recipeimport.tconstruct;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import slimeknights.tconstruct.library.TinkerRegistry;
import slimeknights.tconstruct.library.smeltery.MeltingRecipe;

import java.util.List;

public final class TConstructMeltingSource {
    private TConstructMeltingSource() {
    }

    public static List<ImportedRecipe<MeltingContext>> read(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("tconstruct");
        var sourceRecipes = TinkerRegistry.getAllMeltingRecipies();
        var result = new ObjectArrayList<ImportedRecipe<MeltingContext>>(sourceRecipes.size());
        for (int index = 0; index < sourceRecipes.size(); index++) {
            var source = sourceRecipes.get(index);
            try {
                result.add(convert(RecipeImportSupport.id(poolId, "melting/" + index), source));
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected TConstruct melting recipe {} in pool {}", index, poolId, exception);
            }
        }
        return result;
    }

    public static ImportedRecipe<MeltingContext> convert(ResourceLocation id, MeltingRecipe source) {
        var input = source.input;
        if (input.amountNeeded <= 0)
            throw new IllegalArgumentException("Melting input must consume a positive number of items");
        var candidates = new ObjectArrayList<AEKey>();
        var displayedInputs = input.getInputs();
        for (int candidateIndex = 0; candidateIndex < displayedInputs.size(); candidateIndex++) {
            var stack = displayedInputs.get(candidateIndex);
            if (!stack.isEmpty()) candidates.add(AEItemKey.of(stack));
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("Melting input has no displayed candidates");
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, "input"), (candidate, _) -> {
            if (!(candidate instanceof AEItemKey item)) return false;
            var stack = item.getReadOnlyStack().copy();
            stack.setCount(input.amountNeeded);
            return source.matches(stack);
        });
        var dynamicInput = new DynamicInputGroup(matcher, candidates.getFirst(), input.amountNeeded,
            candidates, Integer.MAX_VALUE);
        var output = RecipeImportSupport.fluid(source.getResult());
        var resources = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(output), false,
            ResourceChance.ALWAYS, null, List.of(), List.of(dynamicInput));
        var recipe = new RecipeBuilder(id, Math.max(1, Math.round(source.temperature / 10F)));
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.id(id, "resources"), resources));
        return new ImportedRecipe<>(recipe,
            new MeltingContext(source, source.getTemperature(), source.getUsableTemperature()));
    }

    public record MeltingContext(MeltingRecipe source, int temperature, int usableTemperature) {
    }
}
