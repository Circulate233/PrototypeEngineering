package com.circulation.prototype_eng.integration.recipeimport.minecraft;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.importing.minecraft.FurnaceRecipePool;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

import java.util.Comparator;
import java.util.List;

/** Vanilla furnace inputs consume one item even when the source stack has a larger count. */
public final class FurnaceRecipeSource {
    private FurnaceRecipeSource() {
    }

    public static List<MachineRecipe> read(ResourceLocation poolId) {
        return read(poolId, null);
    }

    public static List<MachineRecipe> read(ResourceLocation poolId, @Nullable FurnaceRecipePool.OnCreate onCreate) {
        var furnace = FurnaceRecipes.instance();
        var entries = new ObjectArrayList<>(furnace.getSmeltingList().entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().serializeNBT().toString()));
        var recipes = new ObjectArrayList<MachineRecipe>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var input = entry.getKey().copy();
            input.setCount(1);
            var output = entry.getValue().copy();
            var inputKey = AEItemKey.of(input);
            var outputKey = AEItemKey.of(output);
            if (inputKey == null || outputKey == null)
                throw new IllegalArgumentException("Empty furnace ingredient in pool " + poolId);
            var builder = new RecipeBuilder(new ResourceLocation(poolId.getNamespace(), poolId.getPath() + "/furnace/" + i), 200);
            var inputItem = input.getItem();
            int inputMetadata = input.getMetadata();
            var matcher = new NamedResourceMatcher(new ResourceLocation(builder.id().getNamespace(), builder.id().getPath() + "/input"),
                (candidate, _) -> candidate instanceof AEItemKey item && inputItem == item.getItem()
                    && (inputMetadata == OreDictionary.WILDCARD_VALUE
                        || inputMetadata == item.getReadOnlyStack().getMetadata()));
            var ingredient = new DynamicInputGroup(matcher, inputKey, 1, List.of(inputKey), Integer.MAX_VALUE);
            builder.addRequirement(RecipeRequirements.RESOURCES.requirement(
                new ResourceLocation("prototype_eng", "imported_resources"),
                new ResourceRequirement(List.of(), List.of(), List.of(),
                    List.of(new GenericStack(outputKey, output.getCount())), false,
                    ResourceChance.ALWAYS, null, List.of(), List.of(ingredient))));
            if (onCreate == null || onCreate.create(builder, furnace.getSmeltingExperience(output))) recipes.add(builder.build());
        }
        return recipes;
    }
}
