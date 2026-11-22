package com.circulation.prototype_eng.integration.recipeimport.thermalexpansion;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import cofh.thermalexpansion.util.managers.machine.InsolatorManager;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.InsolatorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.InsolatorTreeRecipePool;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class ThermalInsolatorSource {
    private ThermalInsolatorSource() {
    }

    public static List<MachineRecipe> read(ResourceLocation poolId, @Nullable InsolatorRecipePool.OnCreate onCreate) {
        return read(poolId, false, onCreate == null ? null : onCreate::create);
    }

    public static List<MachineRecipe> readTree(ResourceLocation poolId, @Nullable InsolatorTreeRecipePool.OnCreate onCreate) {
        return read(poolId, true, onCreate == null ? null : onCreate::create);
    }

    private static List<MachineRecipe> read(ResourceLocation poolId, boolean tree, @Nullable CreationHandler onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, tree, Arrays.asList(InsolatorManager.getRecipeList()), onCreate);
    }

    @SuppressWarnings("SameParameterValue")
    static List<MachineRecipe> convertAll(ResourceLocation poolId, boolean tree,
                                                             Collection<InsolatorManager.InsolatorRecipe> sources) {
        return convertAll(poolId, tree, sources, null);
    }

    static List<MachineRecipe> convertAll(ResourceLocation poolId, boolean tree,
        Collection<InsolatorManager.InsolatorRecipe> sources, @Nullable CreationHandler onCreate) {
        var ordered = new ObjectArrayList<OrderedSource>(sources.size());
        for (var source : sources) {
            if ((source.getType() == InsolatorManager.Type.TREE) != tree) continue;
            try {
                var key = new StringBuilder();
                appendStack(key, source.getPrimaryInput());
                appendStack(key, source.getSecondaryInput());
                appendStack(key, source.getPrimaryOutput());
                appendStack(key, source.getSecondaryOutput());
                key.append(source.getEnergy()).append(':').append(source.getWater()).append(':')
                    .append(source.getSecondaryOutputChance()).append(':').append(source.hasFertilizer());
                ordered.add(new OrderedSource(key.toString(), source));
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected Thermal Insolator source while sorting pool {}", poolId, exception);
            }
        }
        ordered.sort(Comparator.comparing(OrderedSource::key));
        var result = new ObjectArrayList<MachineRecipe>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            var source = ordered.get(index).source();
            RecipeBuilder recipe;
            try {
                recipe = draft(RecipeImportSupport.id(poolId, "insolator/" + index), source);
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected Thermal Insolator recipe {} in pool {}", index, poolId, exception);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, source.getEnergy(), source.getWater(),
                source.getSecondaryOutputChance(), source.hasFertilizer())) result.add(recipe.build());
        }
        return result;
    }

    private static void appendStack(StringBuilder key, ItemStack stack) {
        String value = stack.getCount() + ":" + stack.getMetadata() + ":" + stack.serializeNBT();
        key.append(value.length()).append(':').append(value);
    }

    private record OrderedSource(String key, InsolatorManager.InsolatorRecipe source) {
    }

    public static MachineRecipe convert(ResourceLocation id, InsolatorManager.InsolatorRecipe source) {
        return draft(id, source).build();
    }

    private static RecipeBuilder draft(ResourceLocation id, InsolatorManager.InsolatorRecipe source) {
        if (source.getEnergy() < 0 || source.getWater() < 0 || source.getSecondaryOutputChance() < 0)
            throw new IllegalArgumentException("Negative Thermal Insolator source quantity");
        var inputs = new ObjectArrayList<GenericStack>(3);
        var dynamic = new ObjectArrayList<DynamicInputGroup>(2);
        dynamic.add(input(id, "primary_input", source.getPrimaryInput()));
        if (!source.getSecondaryInput().isEmpty())
            dynamic.add(input(id, "secondary_input", source.getSecondaryInput()));
        if (source.getWater() > 0)
            inputs.add(RecipeImportSupport.fluid(new FluidStack(FluidRegistry.WATER, source.getWater())));
        var outputs = new ObjectArrayList<GenericStack>(1);
        if (!source.getPrimaryOutput().isEmpty()) outputs.add(RecipeImportSupport.item(source.getPrimaryOutput()));
        if (outputs.isEmpty() && (source.getSecondaryOutput().isEmpty() || source.getSecondaryOutputChance() == 0))
            throw new IllegalArgumentException("Thermal Insolator source has no output");
        int duration = Math.max(1, Math.toIntExact((source.getEnergy() + 19L) / 20L));
        var recipe = new RecipeBuilder(id, duration);
        var resources = new ResourceRequirement(inputs, List.of(), List.of(), outputs, false,
            ResourceChance.ALWAYS, null, List.of(), dynamic);
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.id(id, "resources"), resources));
        if (source.getEnergy() > 0) RecipeImportSupport.energyTotal(recipe, source.getEnergy());
        if (!source.getSecondaryOutput().isEmpty() && source.getSecondaryOutputChance() > 0) {
            var secondaryOutput = new ResourceRequirement(List.of(), List.of(), List.of(),
                List.of(RecipeImportSupport.item(source.getSecondaryOutput())), false,
                new ResourceChance(source.getSecondaryOutputChance(), null), null);
            recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(
                RecipeImportSupport.id(id, "secondary_output"), secondaryOutput));
        }
        return recipe;
    }

    private static DynamicInputGroup input(ResourceLocation id, String suffix, ItemStack stack) {
        var displayed = RecipeImportSupport.item(stack);
        var expected = InsolatorManager.convertInput(stack);
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, suffix),
            (candidate, _) -> candidate instanceof AEItemKey item
                && expected.equals(InsolatorManager.convertInput(item.getReadOnlyStack())));
        return new DynamicInputGroup(matcher, displayed.what(), displayed.amount(),
            List.of(displayed.what()), Integer.MAX_VALUE);
    }

    @FunctionalInterface
    interface CreationHandler {
        boolean create(RecipeBuilder recipe, int energy, int water, int secondaryChance, boolean hasFertilizer);
    }
}
