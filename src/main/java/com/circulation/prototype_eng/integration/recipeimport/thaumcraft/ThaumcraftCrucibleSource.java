package com.circulation.prototype_eng.integration.recipeimport.thaumcraft;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.thaumcraft.CrucibleRecipePool;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.crafting.CrucibleRecipe;

import java.util.List;
import java.util.Map;

public final class ThaumcraftCrucibleSource {
    private ThaumcraftCrucibleSource() {
    }

    public static List<MachineRecipe> read(ResourceLocation poolId, @Nullable CrucibleRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thaumcraft");
        var ordered = new ObjectArrayList<>(ThaumcraftApi.getCraftingRecipes().entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        var result = new ObjectArrayList<MachineRecipe>();
        for (var entry : ordered) {
            if (!(entry.getValue() instanceof CrucibleRecipe source)) continue;
            RecipeBuilder recipe;
            try {
                recipe = draft(RecipeImportSupport.id(poolId, "crucible/" + entry.getKey().getNamespace() + "/" + entry.getKey().getPath()), source);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected Thaumcraft crucible recipe {} in pool {}", entry.getKey(), poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, entry.getKey(), source.getAspects().copy(), source.getResearch())) result.add(recipe.build());
        }
        return result;
    }

    static List<MachineRecipe> convert(ResourceLocation id, ResourceLocation sourceId, CrucibleRecipe source,
        @Nullable CrucibleRecipePool.OnCreate onCreate) {
        var recipe = draft(id, source);
        return onCreate == null || onCreate.create(recipe, sourceId, source.getAspects().copy(), source.getResearch())
            ? List.of(recipe.build()) : List.of();
    }

    private static RecipeBuilder draft(ResourceLocation id, CrucibleRecipe source) {
        var candidates = new ObjectArrayList<AEKey>();
        for (var stack : source.getCatalyst().getMatchingStacks()) {
            var key = AEItemKey.of(stack);
            if (key != null && !candidates.contains(key)) candidates.add(key);
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("Crucible catalyst has no displayable input");
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, "catalyst"),
            (candidate, _) -> candidate instanceof AEItemKey item && source.catalystMatches(item.toStack(1)));
        var dynamic = new DynamicInputGroup(matcher, candidates.getFirst(), 1, candidates, Integer.MAX_VALUE);
        var recipe = new RecipeBuilder(id, 0);
        var resources = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(RecipeImportSupport.item(source.getRecipeOutput())),
            false, ResourceChance.ALWAYS, null, List.of(), List.of(dynamic));
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.requirementId(recipe, "resources"), resources));
        return recipe;
    }
}
