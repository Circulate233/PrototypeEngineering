package com.circulation.prototype_eng.integration.recipeimport.thaumcraft;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
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
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.InfusionRecipe;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ThaumcraftInfusionSource {
    private ThaumcraftInfusionSource() {
    }

    public static List<ImportedRecipe<InfusionContext>> read(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("thaumcraft");
        var entries = new ObjectArrayList<>(ThaumcraftApi.getCraftingRecipes().entrySet());
        entries.sort(Map.Entry.comparingByKey());
        var result = new ObjectArrayList<ImportedRecipe<InfusionContext>>();
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            if (!(entry.getValue() instanceof InfusionRecipe source)) continue;
            try {
                result.addAll(convert(poolId, entry.getKey(), source));
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected Thaumcraft infusion recipe {} in pool {}", entry.getKey(), poolId, exception);
            }
        }
        return result;
    }

    public static List<ImportedRecipe<InfusionContext>> convert(ResourceLocation poolId,
                                                               ResourceLocation sourceId, InfusionRecipe source) {
        var converted = new ObjectArrayList<ImportedRecipe<InfusionContext>>();
        var main = source.getRecipeInput();
        if (main == null) throw new IllegalArgumentException("Infusion source has no main input");
        var output = source.getRecipeOutput();
        var baseId = RecipeImportSupport.id(poolId, "infusion/" + sourceId.getNamespace() + "/" + sourceId.getPath());
        if (output instanceof ItemStack stack) {
            converted.add(convert(baseId, sourceId, source, main, null, RecipeImportSupport.item(stack)));
        } else if (output instanceof Object[] nbtOutput && nbtOutput.length == 2
            && nbtOutput[0] instanceof String tag && nbtOutput[1] instanceof NBTBase value) {
            var mainCandidates = main.getMatchingStacks();
            if (mainCandidates.length == 0) throw new IllegalArgumentException("NBT infusion has no main input candidates");
            for (int candidateIndex = 0; candidateIndex < mainCandidates.length; candidateIndex++) {
                var inputStack = mainCandidates[candidateIndex];
                if (inputStack.isEmpty()) throw new IllegalArgumentException("NBT infusion contains an empty input candidate");
                if (inputStack.getMetadata() == OreDictionary.WILDCARD_VALUE)
                    throw new IllegalArgumentException("NBT infusion requires concrete input metadata");
                var outputStack = inputStack.copy();
                outputStack.setCount(1);
                outputStack.setTagInfo(tag, value.copy());
                var id = RecipeImportSupport.id(baseId, "variant/" + candidateIndex);
                converted.add(convert(id, sourceId, source, main, inputStack,
                    RecipeImportSupport.item(outputStack)));
            }
        } else {
            throw new IllegalArgumentException("Unsupported infusion output: "
                + (output == null ? "null" : output.getClass().getName()));
        }
        return converted;
    }

    private static ImportedRecipe<InfusionContext> convert(ResourceLocation id, ResourceLocation sourceId,
                                                           InfusionRecipe source, Ingredient main,
                                                           ItemStack exactMain, GenericStack output) {
        var inputs = new ObjectArrayList<GenericStack>();
        var dynamic = new ObjectArrayList<DynamicInputGroup>();
        if (exactMain == null) {
            dynamic.add(ingredient(id, "main_input", main));
        } else {
            inputs.add(new GenericStack(Objects.requireNonNull(AEItemKey.of(exactMain), "Empty infusion main input"), 1));
        }
        var components = source.getComponents();
        for (int index = 0; index < components.size(); index++)
            dynamic.add(ingredient(id, "component/" + index, components.get(index)));
        var resources = new ResourceRequirement(inputs, List.of(), List.of(), List.of(output), false,
            ResourceChance.ALWAYS, null, List.of(), dynamic);
        int duration = source.instability == 0 ? 300 : Math.multiplyExact(source.instability, 1000);
        if (duration < 0) throw new IllegalArgumentException("Negative infusion instability");
        var recipe = new RecipeBuilder(id, duration);
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.id(id, "resources"), resources));
        return new ImportedRecipe<>(recipe, new InfusionContext(source, sourceId, source.getAspects().copy(),
            source.instability, source.getResearch(), exactMain == null ? ItemStack.EMPTY : exactMain.copy()));
    }

    private static DynamicInputGroup ingredient(ResourceLocation id, String suffix, Ingredient ingredient) {
        var stacks = ingredient.getMatchingStacks();
        var candidates = new ObjectArrayList<AEKey>(stacks.length);
        for (var stack : stacks) {
            if (!stack.isEmpty()) candidates.add(AEItemKey.of(stack));
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("Infusion ingredient has no displayed candidates");
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, suffix),
            (candidate, _) -> candidate instanceof AEItemKey item && ingredient.apply(item.getReadOnlyStack()));
        return new DynamicInputGroup(matcher, candidates.getFirst(), 1, candidates, Integer.MAX_VALUE);
    }

    /**
     * Aspect and research data remain available to the pool callback for machine-specific requirements.
     */
    public record InfusionContext(InfusionRecipe source, ResourceLocation sourceId, AspectList aspects,
                                   int instability, String research, ItemStack mainInput) {
    }
}
