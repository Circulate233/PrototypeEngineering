package com.circulation.prototype_eng.integration.recipeimport.ic2;

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

public final class Ic2RecipeSources {
    private Ic2RecipeSources() {
    }

    public static List<ImportedRecipe<Ic2RecipeContext>> readCompressor(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "compressor", Objects.requireNonNull(Recipes.compressor, "IC2 compressor recipes are not initialized"));
    }

    public static List<ImportedRecipe<Ic2RecipeContext>> readMacerator(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "macerator", Objects.requireNonNull(Recipes.macerator, "IC2 macerator recipes are not initialized"));
    }

    private static List<ImportedRecipe<Ic2RecipeContext>> read(ResourceLocation poolId, String group,
                                                               IBasicMachineRecipeManager manager) {
        if (!manager.isIterable())
            throw new IllegalStateException("IC2 " + group + " recipe manager does not expose its recipes");
        return convertAll(poolId, group, manager.getRecipes());
    }

    static List<ImportedRecipe<Ic2RecipeContext>> convertAll(ResourceLocation poolId, String group,
                                                              Iterable<? extends MachineRecipe<IRecipeInput, Collection<ItemStack>>> sources) {
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
        var result = new ObjectArrayList<ImportedRecipe<Ic2RecipeContext>>();
        for (int index = 0; index < ordered.size(); index++) {
            var source = ordered.get(index).source();
            try {
                result.add(convert(poolId, group, index, source));
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 recipe {}[{}] while importing pool {}", group, index, poolId, failure);
            }
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

    static ImportedRecipe<Ic2RecipeContext> convert(ResourceLocation poolId, String group, int index,
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
        var metadata = source.getMetaData();
        return new ImportedRecipe<>(recipe, new Ic2RecipeContext(group, index, source,
            metadata == null ? new NBTTagCompound() : metadata.copy()));
    }
}
