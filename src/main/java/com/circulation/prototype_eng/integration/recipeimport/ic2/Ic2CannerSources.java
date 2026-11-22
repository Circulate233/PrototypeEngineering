package com.circulation.prototype_eng.integration.recipeimport.ic2;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.importing.ic2.CannerBottleRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.CannerEnrichRecipePool;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import ic2.api.recipe.ICannerBottleRecipeManager;
import ic2.api.recipe.ICannerEnrichRecipeManager;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.Recipes;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class Ic2CannerSources {
    private Ic2CannerSources() {
    }

    public static List<MachineRecipe> readBottle(ResourceLocation poolId, @Nullable CannerBottleRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        var manager = Objects.requireNonNull(Recipes.cannerBottle, "IC2 canner bottle recipes are not initialized");
        if (!manager.isIterable()) throw new IllegalStateException("IC2 canner bottle recipes cannot be enumerated");
        return bottles(poolId, manager.getRecipes(), onCreate);
    }

    public static List<MachineRecipe> readEnrich(ResourceLocation poolId, @Nullable CannerEnrichRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        var manager = Objects.requireNonNull(Recipes.cannerEnrich, "IC2 canner enriching recipes are not initialized");
        if (!manager.isIterable()) throw new IllegalStateException("IC2 canner enriching recipes cannot be enumerated");
        return enrichments(poolId, manager.getRecipes(), onCreate);
    }

    static List<MachineRecipe> bottles(ResourceLocation poolId,
        Iterable<? extends ic2.api.recipe.MachineRecipe<ICannerBottleRecipeManager.Input, ItemStack>> sources,
        @Nullable CannerBottleRecipePool.OnCreate onCreate) {
        var ordered = new ObjectArrayList<ic2.api.recipe.MachineRecipe<ICannerBottleRecipeManager.Input, ItemStack>>();
        sources.forEach(ordered::add);
        ordered.sort(Comparator.comparing(source -> inputKey(source.getInput().container) + inputKey(source.getInput().fill)
            + source.getOutput().serializeNBT() + source.getMetaData()));
        var result = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < ordered.size(); index++) {
            var source = ordered.get(index);
            RecipeBuilder recipe;
            try {
                recipe = bottle(RecipeImportSupport.id(poolId, "canner_bottle/" + index), source);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 canner bottle recipe {} in pool {}", index, poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, metadata(source.getMetaData()))) result.add(recipe.build());
        }
        return result;
    }

    static List<MachineRecipe> enrichments(ResourceLocation poolId,
        Iterable<? extends ic2.api.recipe.MachineRecipe<ICannerEnrichRecipeManager.Input, FluidStack>> sources,
        @Nullable CannerEnrichRecipePool.OnCreate onCreate) {
        var ordered = new ObjectArrayList<ic2.api.recipe.MachineRecipe<ICannerEnrichRecipeManager.Input, FluidStack>>();
        sources.forEach(ordered::add);
        ordered.sort(Comparator.comparing(source -> source.getInput().fluid.writeToNBT(new NBTTagCompound())
            + inputKey(source.getInput().additive) + source.getOutput().writeToNBT(new NBTTagCompound()) + source.getMetaData()));
        var result = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < ordered.size(); index++) {
            var source = ordered.get(index);
            RecipeBuilder recipe;
            try {
                recipe = enrich(RecipeImportSupport.id(poolId, "canner_enrich/" + index), source);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 canner enriching recipe {} in pool {}", index, poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, metadata(source.getMetaData()))) result.add(recipe.build());
        }
        return result;
    }

    private static RecipeBuilder bottle(ResourceLocation id,
        ic2.api.recipe.MachineRecipe<ICannerBottleRecipeManager.Input, ItemStack> source) {
        var recipe = new RecipeBuilder(id, 200);
        var resources = new ResourceRequirement(List.of(), List.of(), List.of(),
            List.of(RecipeImportSupport.item(source.getOutput())), false, ResourceChance.ALWAYS, null, List.of(),
            List.of(Ic2RecipeSources.input(id, "container", source.getInput().container),
                Ic2RecipeSources.input(id, "fill", source.getInput().fill)));
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.requirementId(recipe, "resources"), resources));
        RecipeImportSupport.energy(recipe, 16);
        return recipe;
    }

    private static RecipeBuilder enrich(ResourceLocation id,
        ic2.api.recipe.MachineRecipe<ICannerEnrichRecipeManager.Input, FluidStack> source) {
        var recipe = new RecipeBuilder(id, 200);
        var resources = new ResourceRequirement(List.of(RecipeImportSupport.fluid(source.getInput().fluid)), List.of(), List.of(),
            List.of(RecipeImportSupport.fluid(source.getOutput())), false, ResourceChance.ALWAYS, null, List.of(),
            List.of(Ic2RecipeSources.input(id, "additive", source.getInput().additive)));
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.requirementId(recipe, "resources"), resources));
        RecipeImportSupport.energy(recipe, 16);
        return recipe;
    }

    private static String inputKey(IRecipeInput input) {
        var candidates = new ObjectArrayList<String>();
        for (var stack : input.getInputs()) candidates.add(stack.serializeNBT().toString());
        candidates.sort(Comparator.naturalOrder());
        return input.getAmount() + ":" + candidates;
    }

    private static NBTTagCompound metadata(@Nullable NBTTagCompound source) {
        return source == null ? new NBTTagCompound() : source.copy();
    }
}
