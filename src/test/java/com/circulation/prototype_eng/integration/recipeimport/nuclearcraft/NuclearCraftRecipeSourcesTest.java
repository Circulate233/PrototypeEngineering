package com.circulation.prototype_eng.integration.recipeimport.nuclearcraft;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import nc.recipe.BasicRecipe;
import nc.recipe.ingredient.ChanceFluidIngredient;
import nc.recipe.ingredient.ChanceItemIngredient;
import nc.recipe.ingredient.FluidArrayIngredient;
import nc.recipe.ingredient.FluidIngredient;
import nc.recipe.ingredient.ItemArrayIngredient;
import nc.recipe.ingredient.ItemIngredient;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NuclearCraftRecipeSourcesTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void expandsDifferentQuantityAlternativesAndRetainsBothItemAndFluidInputs() {
        var items = new ItemArrayIngredient(new ItemIngredient(new ItemStack(Items.IRON_INGOT, 2)),
            new ItemIngredient(new ItemStack(Items.GOLD_INGOT, 4)));
        var fluids = new FluidArrayIngredient(new FluidIngredient(new FluidStack(FluidRegistry.WATER, 125)),
            new FluidIngredient(new FluidStack(FluidRegistry.LAVA, 250)));
        var extras = new ObjectArrayList<Object>(List.of(1.75D, 2.5D, 0.125D));
        var source = new BasicRecipe(List.of(items), List.of(fluids),
            List.of(new ItemIngredient(new ItemStack(Items.DIAMOND))), List.of(), extras, true);
        var capturedExtras = new AtomicReference<List<Object>>();
        var variants = NuclearCraftRecipeSources.convert(id("alternatives"), "infuser", 0, source, 8, 4,
            (_, duration, energy, radiation, copiedExtras) -> {
                assertEquals(source.getBaseProcessTime(8), duration);
                assertEquals(source.getBaseProcessPower(4), energy);
                assertEquals(source.getBaseProcessRadiation(), radiation);
                capturedExtras.set(copiedExtras);
                return true;
            });
        assertEquals(4, variants.size());
        MachineRecipe selected = null;
        var gold = Objects.requireNonNull(AEItemKey.of(new ItemStack(Items.GOLD_INGOT)));
        var iron = Objects.requireNonNull(AEItemKey.of(new ItemStack(Items.IRON_INGOT)));
        var lava = Objects.requireNonNull(AEFluidKey.of(new FluidStack(FluidRegistry.LAVA, 1)));
        var water = Objects.requireNonNull(AEFluidKey.of(new FluidStack(FluidRegistry.WATER, 1)));
        for (var variant : variants) {
            var inputs = inputs(variant);
            assertEquals(2, inputs.size());
            var item = inputs.getFirst();
            var fluid = inputs.getLast();
            assertTrue(item.matcher().matches(item.amount() == 2 ? iron : gold, item.pattern()));
            assertFalse(item.matcher().matches(item.amount() == 2 ? gold : iron, item.pattern()));
            assertTrue(fluid.matcher().matches(fluid.amount() == 125 ? water : lava, fluid.pattern()));
            assertFalse(fluid.matcher().matches(fluid.amount() == 125 ? lava : water, fluid.pattern()));
            if (item.amount() == 4 && fluid.amount() == 250) selected = variant;
        }
        var imported = Objects.requireNonNull(selected);
        extras.set(0, 99D);
        assertEquals(List.of(1.75D, 2.5D, 0.125D), capturedExtras.get());

        var input = hatch(HatchMode.INPUT, 2);
        var output = hatch(HatchMode.OUTPUT, 1);
        var material = Objects.requireNonNull(input.input());
        material.insert(gold, 4, Actionable.MODULATE);
        material.insert(lava, 249, Actionable.MODULATE);
        var process = process(materialOnly(imported).build(), input, output, 7);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(4, material.availableAmount(gold));
        assertEquals(249, material.availableAmount(lava));
        material.insert(lava, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, material.amount(gold));
        assertEquals(0, material.amount(lava));
        assertEquals(1, Objects.requireNonNull(output.output()).amount(AEItemKey.of(new ItemStack(Items.DIAMOND))));
    }

    @Test
    void chanceOutputsKeepGuaranteedMinimumAndIndependentItemAndFluidSteps() {
        var item = new ChanceItemIngredient(new ItemIngredient(new ItemStack(Items.DIAMOND, 5)), 50, 2);
        var fluid = new ChanceFluidIngredient(new FluidIngredient(new FluidStack(FluidRegistry.WATER, 350)), 50, 50, 100);
        var source = new BasicRecipe(List.of(), List.of(), List.of(item), List.of(fluid), List.of(1D, 1D, 0D), true);
        var imported = NuclearCraftRecipeSources.convert(id("chance"), "chemical_reactor", 0, source, 1, 0).getFirst();
        var recipe = materialOnly(imported).build();
        var diamond = Objects.requireNonNull(AEItemKey.of(new ItemStack(Items.DIAMOND)));
        var water = Objects.requireNonNull(AEFluidKey.of(new FluidStack(FluidRegistry.WATER, 1)));
        var itemAmounts = new LongOpenHashSet();
        var fluidAmounts = new LongOpenHashSet();
        var seeds = new Random(23);
        for (int sample = 0; sample < 128; sample++) {
            var output = hatch(HatchMode.OUTPUT, 2);
            var process = process(recipe, null, output, seeds.nextLong());
            assertSame(ProcessResult.SUCCESS, process.tick());
            var products = Objects.requireNonNull(output.output());
            long itemCount = products.amount(diamond);
            long fluidCount = products.amount(water);
            assertTrue(itemCount >= item.getMinStackSize() && itemCount <= item.getMaxStackSize(0));
            assertTrue(fluidCount >= fluid.getMinStackSize() && fluidCount <= fluid.getMaxStackSize(0));
            assertEquals(0, (fluidCount - fluid.getMinStackSize()) % fluid.getStackDiff());
            itemAmounts.add(itemCount);
            fluidAmounts.add(fluidCount);
        }
        assertTrue(itemAmounts.size() > 2, "Independent item trials must produce intermediate quantities");
        assertTrue(fluidAmounts.size() > 2, "Independent fluid trials must preserve their configured step size");
    }

    @Test
    void chanceBoundariesPreserveMinimumMaximumAndParallelAmounts() {
        for (int percent : new int[]{0, 100}) {
            var item = new ChanceItemIngredient(new ItemIngredient(new ItemStack(Items.DIAMOND, 5)), percent, 2);
            var fluid = new ChanceFluidIngredient(new FluidIngredient(new FluidStack(FluidRegistry.WATER, 350)), percent, 50, 100);
            var source = new BasicRecipe(List.of(), List.of(), List.of(item), List.of(fluid), List.of(1D, 1D, 0D), true);
            var imported = NuclearCraftRecipeSources.convert(id("boundary/" + percent), "chemical_reactor", 0, source, 1, 0).getFirst();
            var output = hatch(HatchMode.OUTPUT, 2);
            var owner = new MachineInstance();
            owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
            owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(output), List.of(), List.of()));
            var process = new RecipeProcess(owner, materialOnly(imported).build(), 1);
            assertSame(ProcessResult.SUCCESS, process.tick());
            var products = Objects.requireNonNull(output.output());
            assertEquals(2L * (percent == 0 ? item.getMinStackSize() : item.getMaxStackSize(0)),
                products.amount(AEItemKey.of(new ItemStack(Items.DIAMOND))));
            assertEquals(2L * (percent == 0 ? fluid.getMinStackSize() : fluid.getMaxStackSize(0)),
                products.amount(AEFluidKey.of(new FluidStack(FluidRegistry.WATER, 1))));
        }
    }

    @Test
    void nullHandlerSkipsRadiationDataAndFalseOrFailureStopsRecipeCreation() {
        var source = new BasicRecipe(List.of(), List.of(), List.of(new ItemIngredient(new ItemStack(Items.DIAMOND))),
            List.of(), List.of(1D, 1D, 0D), true) {
            @Override
            public double getBaseProcessRadiation() {
                throw new AssertionError("Radiation is only needed by a creation handler");
            }
        };
        assertEquals(1, NuclearCraftRecipeSources.convert(id("no_handler"), "infuser", 0, source, 8, 4).size());
        var ordinary = new BasicRecipe(List.of(), List.of(), List.of(new ItemIngredient(new ItemStack(Items.DIAMOND))),
            List.of(), List.of(1D, 1D, 0D), true);
        assertTrue(NuclearCraftRecipeSources.convert(id("rejected"), "infuser", 0, ordinary, 8, 4,
            (_, _, _, _, _) -> false).isEmpty());
        var failure = new IllegalStateException("Creation handler failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> NuclearCraftRecipeSources.convert(id("failed"), "infuser", 0, ordinary, 8, 4, (_, _, _, _, _) -> {
                throw failure;
            })));
    }

    private static List<DynamicInputGroup> inputs(MachineRecipe recipe) {
        var result = new ObjectArrayList<DynamicInputGroup>();
        for (var requirement : recipe.orderedRequirements()) {
            if (requirement.type() == RecipeRequirements.RESOURCES)
                result.addAll(((ResourceRequirement) requirement.payload()).dynamicInputs());
        }
        return result;
    }

    private static RecipeBuilder materialOnly(MachineRecipe source) {
        var result = new RecipeBuilder(source.id(), 0);
        for (var requirement : source.orderedRequirements())
            if (requirement.type() == RecipeRequirements.RESOURCES) result.addRequirement(requirement);
        return result;
    }

    private static RecipeProcess process(MachineRecipe recipe,
                                         BufferedResourceHatch input, BufferedResourceHatch output, long seed) {
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, new MachineHatches(
            input == null ? List.of(output) : List.of(input, output), List.of(), List.of()));
        return new RecipeProcess(owner, recipe, seed);
    }

    private static BufferedResourceHatch hatch(HatchMode mode, int slots) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(AEKeyType.items(), 1000);
        capacities.put(AEKeyType.fluids(), 1000);
        return new BufferedResourceHatch(mode, slots, capacities, () -> {
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("nc_import_test", path);
    }
}
