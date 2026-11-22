package com.circulation.prototype_eng.integration.recipeimport.ic2;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ic2.CompressorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.MaceratorRecipePool;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import ic2.api.recipe.IBasicMachineRecipeManager;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.Recipes;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

public final class Ic2RecipeSources {
    private Ic2RecipeSources() {
    }

    @SuppressWarnings("unused")
    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readCompressor(ResourceLocation poolId) {
        return readCompressor(poolId, null);
    }

    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readCompressor(ResourceLocation poolId,
        @Nullable CompressorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "compressor", Objects.requireNonNull(Recipes.compressor, "IC2 compressor recipes are not initialized"),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readMacerator(ResourceLocation poolId) {
        return readMacerator(poolId, null);
    }

    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readMacerator(ResourceLocation poolId,
        @Nullable MaceratorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "macerator", Objects.requireNonNull(Recipes.macerator, "IC2 macerator recipes are not initialized"),
            onCreate == null ? null : onCreate::create);
    }

    private static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> read(ResourceLocation poolId, String group,
                                                               IBasicMachineRecipeManager manager, @Nullable BiPredicate<RecipeBuilder, NBTTagCompound> onCreate) {
        if (!manager.isIterable())
            throw new IllegalStateException("IC2 " + group + " recipe manager does not expose its recipes");
        return convertAll(poolId, group, manager.getRecipes(), onCreate);
    }

    @SuppressWarnings("SameParameterValue")
    static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> convertAll(ResourceLocation poolId, String group,
                                                              Iterable<? extends MachineRecipe<IRecipeInput, Collection<ItemStack>>> sources) {
        return convertAll(poolId, group, sources, null);
    }

    static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> convertAll(ResourceLocation poolId, String group,
        Iterable<? extends MachineRecipe<IRecipeInput, Collection<ItemStack>>> sources,
        @Nullable BiPredicate<RecipeBuilder, NBTTagCompound> onCreate) {
        var ordered = new ObjectArrayList<OrderedSource>();
        for (var source : sources) {
            try {
                var key = new StringBuilder();
                key.append(source.getInput().getAmount()).append(':');
                appendStacks(key, source.getInput().getInputs());
                appendStacks(key, source.getOutput());
                var metadata = source.getMetaData();
                appendValue(key, metadata == null ? "{}" : metadata.toString());
                ordered.add(new OrderedSource(key.toString(), source));
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 source while sorting {} recipes for pool {}", group, poolId, failure);
            }
        }
        ordered.sort(Comparator.comparing(OrderedSource::key));
        var result = new ObjectArrayList<com.circulation.prototype_eng.api.recipe.MachineRecipe>();
        for (int index = 0; index < ordered.size(); index++) {
            var source = ordered.get(index).source();
            RecipeBuilder recipe;
            try {
                recipe = draft(poolId, group, index, source);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 recipe {}[{}] while importing pool {}", group, index, poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.test(recipe, metadata(source))) result.add(recipe.build());
        }
        return result;
    }

    private static void appendStacks(StringBuilder key, Collection<ItemStack> stacks) {
        var values = new ObjectArrayList<String>(stacks.size());
        for (var stack : stacks) {
            if (stack != null && !stack.isEmpty())
                values.add(stack.getCount() + ":" + stack.getMetadata() + ":" + stack.serializeNBT());
        }
        values.sort(Comparator.naturalOrder());
        key.append(values.size()).append(':');
        for (var value : values) appendValue(key, value);
    }

    private static void appendValue(StringBuilder key, String value) {
        key.append(value.length()).append(':').append(value);
    }

    private record OrderedSource(String key, MachineRecipe<IRecipeInput, Collection<ItemStack>> source) {
    }

    @SuppressWarnings("SameParameterValue")
    static com.circulation.prototype_eng.api.recipe.MachineRecipe convert(ResourceLocation poolId, String group, int index,
        MachineRecipe<IRecipeInput, Collection<ItemStack>> source) {
        return draft(poolId, group, index, source).build();
    }

    private static RecipeBuilder draft(ResourceLocation poolId, String group, int index,
        MachineRecipe<IRecipeInput, Collection<ItemStack>> source) {
        var input = source.getInput();
        int amount = input.getAmount();
        if (amount < 1) throw new IllegalArgumentException("IC2 input amount must be positive");
        var displayed = new ObjectArrayList<AEKey>();
        for (var stack : input.getInputs()) {
            if (stack == null || stack.isEmpty()) continue;
            var key = AEItemKey.of(stack);
            if (key != null && !displayed.contains(key)) displayed.add(key);
        }
        if (displayed.isEmpty()) throw new IllegalArgumentException("IC2 input has no displayable candidate");
        var recipeId = RecipeImportSupport.id(poolId, "ic2/" + group + "/" + index);
        var recipe = new RecipeBuilder(recipeId, 300);
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(recipeId, "input_matcher"),
            (candidate, _) -> candidate instanceof AEItemKey key && input.matches(key.toStack(amount)));
        var dynamic = new DynamicInputGroup(matcher, displayed.getFirst(), amount,
            List.copyOf(displayed), Integer.MAX_VALUE);
        var outputs = new ObjectArrayList<GenericStack>();
        for (var output : source.getOutput()) outputs.add(RecipeImportSupport.item(output));
        if (outputs.isEmpty()) throw new IllegalArgumentException("IC2 recipe has no output");
        var resources = new ResourceRequirement(List.of(), List.of(), List.of(), List.copyOf(outputs), false,
            ResourceChance.ALWAYS, null, List.of(), List.of(dynamic));
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(
            RecipeImportSupport.requirementId(recipe, "resources"), resources));
        RecipeImportSupport.energy(recipe, 8);
        return recipe;
    }
    private static NBTTagCompound metadata(MachineRecipe<IRecipeInput, Collection<ItemStack>> source) {
        var metadata = source.getMetaData();
        return metadata == null ? new NBTTagCompound() : metadata.copy();
    }
}
