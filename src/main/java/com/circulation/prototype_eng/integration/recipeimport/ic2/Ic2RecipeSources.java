package com.circulation.prototype_eng.integration.recipeimport.ic2;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ic2.CompressorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.MaceratorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.ExtractorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.CentrifugeRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.BlockCutterRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.MetalFormerExtrudingRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.MetalFormerCuttingRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.MetalFormerRollingRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.OreWashingRecipePool;
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
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

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

public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readExtractor(ResourceLocation poolId,
        @Nullable ExtractorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "extractor", Objects.requireNonNull(Recipes.extractor, "IC2 extractor recipes are not initialized"),
            onCreate == null ? null : onCreate::create, 300, 8, false);
    }


    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readCentrifuge(ResourceLocation poolId,
        @Nullable CentrifugeRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "centrifuge", Objects.requireNonNull(Recipes.centrifuge, "IC2 centrifuge recipes are not initialized"),
            onCreate == null ? null : onCreate::create, 500, 192, false);
    }


    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readBlockCutter(ResourceLocation poolId,
        @Nullable BlockCutterRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "blockcutter", Objects.requireNonNull(Recipes.blockcutter, "IC2 blockcutter recipes are not initialized"),
            onCreate == null ? null : onCreate::create, 450, 16, false);
    }


    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readMetalFormerExtruding(ResourceLocation poolId,
        @Nullable MetalFormerExtrudingRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "metalformerExtruding", Objects.requireNonNull(Recipes.metalformerExtruding, "IC2 metalformerExtruding recipes are not initialized"),
            onCreate == null ? null : onCreate::create, 200, 40, false);
    }


    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readMetalFormerCutting(ResourceLocation poolId,
        @Nullable MetalFormerCuttingRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "metalformerCutting", Objects.requireNonNull(Recipes.metalformerCutting, "IC2 metalformerCutting recipes are not initialized"),
            onCreate == null ? null : onCreate::create, 200, 40, false);
    }


    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readMetalFormerRolling(ResourceLocation poolId,
        @Nullable MetalFormerRollingRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "metalformerRolling", Objects.requireNonNull(Recipes.metalformerRolling, "IC2 metalformerRolling recipes are not initialized"),
            onCreate == null ? null : onCreate::create, 200, 40, false);
    }


    public static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> readOreWashing(ResourceLocation poolId,
        @Nullable OreWashingRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return read(poolId, "oreWashing", Objects.requireNonNull(Recipes.oreWashing, "IC2 oreWashing recipes are not initialized"),
            onCreate == null ? null : onCreate::create, 500, 64, true);
    }

    private static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> read(ResourceLocation poolId, String group,
                                                               IBasicMachineRecipeManager manager, @Nullable BiPredicate<RecipeBuilder, NBTTagCompound> onCreate) {
        return read(poolId, group, manager, onCreate, 300, 8, false);
    }

    private static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> read(ResourceLocation poolId, String group,
        IBasicMachineRecipeManager manager, @Nullable BiPredicate<RecipeBuilder, NBTTagCompound> onCreate,
        int durationTicks, long energyPerTick, boolean needsWater) {
        if (!manager.isIterable())
            throw new IllegalStateException("IC2 " + group + " recipe manager does not expose its recipes");
        return convertAll(poolId, group, manager.getRecipes(), onCreate, durationTicks, energyPerTick, needsWater);
    }

    @SuppressWarnings("SameParameterValue")
    static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> convertAll(ResourceLocation poolId, String group,
                                                              Iterable<? extends MachineRecipe<IRecipeInput, Collection<ItemStack>>> sources) {
        return convertAll(poolId, group, sources, null);
    }

    static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> convertAll(ResourceLocation poolId, String group,
        Iterable<? extends MachineRecipe<IRecipeInput, Collection<ItemStack>>> sources,
        @Nullable BiPredicate<RecipeBuilder, NBTTagCompound> onCreate) {
        return convertAll(poolId, group, sources, onCreate, 300, 8, false);
    }

    static List<com.circulation.prototype_eng.api.recipe.MachineRecipe> convertAll(ResourceLocation poolId, String group,
        Iterable<? extends MachineRecipe<IRecipeInput, Collection<ItemStack>>> sources,
        @Nullable BiPredicate<RecipeBuilder, NBTTagCompound> onCreate, int durationTicks, long energyPerTick, boolean needsWater) {
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
                recipe = draft(poolId, group, index, source, durationTicks, energyPerTick, needsWater);
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
        return draft(poolId, group, index, source, 300, 8, false).build();
    }

    private static RecipeBuilder draft(ResourceLocation poolId, String group, int index,
        MachineRecipe<IRecipeInput, Collection<ItemStack>> source, int durationTicks, long energyPerTick, boolean needsWater) {
        var input = source.getInput();
        var recipeId = RecipeImportSupport.id(poolId, "ic2/" + group + "/" + index);
        var recipe = new RecipeBuilder(recipeId, durationTicks);
        var dynamic = input(recipeId, "input_matcher", input);
        var outputs = new ObjectArrayList<GenericStack>();
        for (var output : source.getOutput()) outputs.add(RecipeImportSupport.item(output));
        if (outputs.isEmpty()) throw new IllegalArgumentException("IC2 recipe has no output");
        List<GenericStack> fixedInputs = List.of();
        if (needsWater) {
            var metadata = Objects.requireNonNull(source.getMetaData(), "IC2 ore washing metadata");
            int amountWater = metadata.getInteger("amount");
            if (amountWater <= 0) throw new IllegalArgumentException("IC2 ore washing water amount must be positive");
            fixedInputs = List.of(RecipeImportSupport.fluid(new FluidStack(FluidRegistry.WATER, amountWater)));
        }
        var resources = new ResourceRequirement(fixedInputs, List.of(), List.of(), List.copyOf(outputs), false,
            ResourceChance.ALWAYS, null, List.of(), List.of(dynamic));
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(
            RecipeImportSupport.requirementId(recipe, "resources"), resources));
        RecipeImportSupport.energy(recipe, energyPerTick);
        return recipe;
    }

    static DynamicInputGroup input(ResourceLocation recipeId, String name, IRecipeInput input) {
        int amount = input.getAmount();
        if (amount < 1) throw new IllegalArgumentException("IC2 input amount must be positive");
        var displayed = new ObjectArrayList<AEKey>();
        for (var stack : input.getInputs()) {
            if (stack == null || stack.isEmpty()) continue;
            var key = AEItemKey.of(stack);
            if (key != null && !displayed.contains(key)) displayed.add(key);
        }
        if (displayed.isEmpty()) throw new IllegalArgumentException("IC2 input has no displayable candidate");
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(recipeId, name),
            (candidate, _) -> candidate instanceof AEItemKey key && input.matches(key.toStack(amount)));
        return new DynamicInputGroup(matcher, displayed.getFirst(), amount,
            List.copyOf(displayed), Integer.MAX_VALUE);
    }
    private static NBTTagCompound metadata(MachineRecipe<IRecipeInput, Collection<ItemStack>> source) {
        var metadata = source.getMetaData();
        return metadata == null ? new NBTTagCompound() : metadata.copy();
    }
}
