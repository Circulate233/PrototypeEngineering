package com.circulation.prototype_eng.integration.recipeimport.nuclearcraft;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import nc.recipe.BasicRecipe;
import nc.recipe.ingredient.EmptyFluidIngredient;
import nc.recipe.ingredient.FluidIngredient;
import nc.recipe.ingredient.ItemIngredient;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.Fluid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NuclearCraftMultiblockSourcesTest {
    @BeforeAll
    static void initializeMinecraft() {
        Bootstrap.register();
    }

    @Test
    void electrolyzerPassesItsElectrolyteGroupAndKeepsAllFourInputKindsAndProducts() {
        var extras = new ObjectArrayList<Object>(List.of(1.5D, 2.5D, 0.125D, "test_electrolytes"));
        var source = new BasicRecipe(List.of(item(Items.IRON_INGOT, 2), item(Items.GOLD_INGOT, 3)),
            List.of(fluid(FluidRegistry.WATER, 125), fluid(FluidRegistry.LAVA, 250)),
            List.of(item(Items.DIAMOND, 4), item(Items.EMERALD, 5)),
            List.of(fluid(FluidRegistry.WATER, 50), fluid(FluidRegistry.LAVA, 75)), extras, true);
        var calls = new AtomicInteger();
        var captured = new AtomicReference<List<Object>>();
        var recipe = NuclearCraftRecipeSources.convertMultiblockElectrolyzer(id("electrolyzer"), 0, source, 2.5, 1.5,
            (_, duration, power, radiation, copiedExtras, electrolyteGroup) -> {
                calls.incrementAndGet();
                assertEquals(source.getBaseProcessTime(2.5), duration);
                assertEquals(source.getBaseProcessPower(1.5), power);
                assertEquals(source.getBaseProcessRadiation(), radiation);
                assertEquals("test_electrolytes", electrolyteGroup);
                captured.set(copiedExtras);
                return true;
            }).getFirst();
        assertEquals(1, calls.get());
        assertEquals(4, dynamicInputCount(recipe));
        extras.set(3, "changed");
        assertEquals("test_electrolytes", captured.get().get(3));
        executeWithAllSourceMaterials(source, recipe);
    }

    @Test
    void distillerCountsOnlyNonemptyProductsAsSieveTraysAndPreservesEveryFluidOutput() {
        var source = new BasicRecipe(List.of(), List.of(fluid(FluidRegistry.WATER, 125), fluid(FluidRegistry.LAVA, 250)),
            List.of(), List.of(fluid(FluidRegistry.WATER, 10), new EmptyFluidIngredient(),
                fluid(FluidRegistry.WATER, 20), fluid(FluidRegistry.LAVA, 30)), List.of(1.25D, 1.5D, 0.0625D), true);
        var recipe = NuclearCraftRecipeSources.convertMultiblockDistiller(id("distiller"), 0, source, 2.5, 1.5,
            (_, duration, power, radiation, extras, sieveTrayCount) -> {
                assertEquals(source.getBaseProcessTime(2.5), duration);
                assertEquals(source.getBaseProcessPower(1.5), power);
                assertEquals(source.getBaseProcessRadiation(), radiation);
                assertEquals(source.getExtras(), extras);
                assertEquals(source.getDistillerSieveTrayCount(), sieveTrayCount);
                return true;
            }).getFirst();
        assertEquals(2, dynamicInputCount(recipe));
        executeWithAllSourceMaterials(source, recipe);
    }

    @Test
    void infiltratorPassesHeatingFactorAndCreationHandlerCanRejectOrFail() {
        var source = new BasicRecipe(List.of(item(Items.IRON_INGOT, 2), item(Items.GOLD_INGOT, 3)),
            List.of(fluid(FluidRegistry.WATER, 125), fluid(FluidRegistry.LAVA, 250)),
            List.of(item(Items.DIAMOND, 4)), List.of(), List.of(1.75D, 2.5D, 0.125D, 0.375D), true);
        var accepted = NuclearCraftRecipeSources.convertMultiblockInfiltrator(id("infiltrator"), 0, source, 2.5, 1.5,
            (_, duration, power, radiation, extras, heatingFactor) -> {
                assertEquals(source.getBaseProcessTime(2.5), duration);
                assertEquals(source.getBaseProcessPower(1.5), power);
                assertEquals(source.getBaseProcessRadiation(), radiation);
                assertEquals(source.getExtras(), extras);
                assertEquals(source.getInfiltratorHeatingFactor(), heatingFactor);
                return true;
            });
        assertFalse(accepted.isEmpty());
        executeWithAllSourceMaterials(source, accepted.getFirst());
        assertTrue(NuclearCraftRecipeSources.convertMultiblockInfiltrator(id("rejected"), 0, source, 2.5, 1.5,
            (_, _, _, _, _, _) -> false).isEmpty());
        var failure = new IllegalStateException("Infiltrator callback failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> NuclearCraftRecipeSources.convertMultiblockInfiltrator(id("failed"), 0, source, 2.5, 1.5,
                (_, _, _, _, _, _) -> {
                    throw failure;
                })));
    }

    @Test
    void nullMultiblockHandlersDoNotReadDedicatedMetadata() {
        var source = new BasicRecipe(List.of(item(Items.IRON_INGOT, 2)), List.of(), List.of(item(Items.DIAMOND, 3)),
            List.of(), List.of(1D, 1D, 0D), true) {
            @Override
            public long getDistillerSieveTrayCount() {
                throw new AssertionError("Sieve tray metadata must not be read without a callback");
            }

            @Override
            public double getInfiltratorHeatingFactor() {
                throw new AssertionError("Heating metadata must not be read without a callback");
            }

            @Override
            public double getBaseProcessRadiation() {
                throw new AssertionError("Radiation metadata must not be read without a callback");
            }
        };
        assertEquals(1, NuclearCraftRecipeSources.convertMultiblockElectrolyzer(id("no_electrolyte_callback"), 0, source, 2.5, 1.5, null).size());
        assertEquals(1, NuclearCraftRecipeSources.convertMultiblockDistiller(id("no_distiller_callback"), 0, source, 2.5, 1.5, null).size());
        assertEquals(1, NuclearCraftRecipeSources.convertMultiblockInfiltrator(id("no_infiltrator_callback"), 0, source, 2.5, 1.5, null).size());
    }

    private static void executeWithAllSourceMaterials(BasicRecipe source, MachineRecipe recipe) {
        var input = hatch(HatchMode.INPUT);
        var output = hatch(HatchMode.OUTPUT);
        var materials = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        for (var ingredient : source.getItemIngredients()) {
            if (!ingredient.isEmpty()) materials.insert(Objects.requireNonNull(AEItemKey.of(ingredient.getStack())), ingredient.getMaxStackSize(0), Actionable.MODULATE);
        }
        for (var ingredient : source.getFluidIngredients()) {
            if (!ingredient.isEmpty()) materials.insert(Objects.requireNonNull(AEFluidKey.of(ingredient.getStack())), ingredient.getMaxStackSize(0), Actionable.MODULATE);
        }
        var energy = new EnergyBuffer(1000, () -> {
        });
        energy.receiveEnergy(1000, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(input, output), List.of(energy), List.of()));
        var process = new RecipeProcess(machine, recipe, 97);
        for (int tick = 0; tick < recipe.durationTicks(); tick++) assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        var expected = new AEKeyLinkedOpenHashMap(4);
        for (var ingredient : source.getItemProducts()) {
            if (!ingredient.isEmpty()) expected.addTo(Objects.requireNonNull(AEItemKey.of(ingredient.getStack())), ingredient.getMaxStackSize(0));
        }
        for (var ingredient : source.getFluidProducts()) {
            if (!ingredient.isEmpty()) expected.addTo(Objects.requireNonNull(AEFluidKey.of(ingredient.getStack())), ingredient.getMaxStackSize(0));
        }
        for (var entry : expected.object2LongEntrySet()) assertEquals(entry.getLongValue(), products.amount(entry.getKey()));
        for (var ingredient : source.getItemIngredients())
            if (!ingredient.isEmpty()) assertEquals(0, materials.amount(AEItemKey.of(ingredient.getStack())));
        for (var ingredient : source.getFluidIngredients())
            if (!ingredient.isEmpty()) assertEquals(0, materials.amount(AEFluidKey.of(ingredient.getStack())));
    }

    private static int dynamicInputCount(MachineRecipe recipe) {
        int result = 0;
        for (var binding : recipe.requirements().get(RecipeRequirements.RESOURCES))
            result += ((ResourceRequirement) binding.payload()).dynamicInputs().size();
        return result;
    }

    private static ItemIngredient item(Item item, int count) {
        return new ItemIngredient(new ItemStack(item, count));
    }

    private static FluidIngredient fluid(Fluid fluid, int amount) {
        return new FluidIngredient(new FluidStack(fluid, amount));
    }

    private static BufferedResourceHatch hatch(HatchMode mode) {
        var capacity = new Reference2LongArrayMap<AEKeyType>();
        capacity.put(AEKeyType.items(), 1000);
        capacity.put(AEKeyType.fluids(), 1000);
        return new BufferedResourceHatch(mode, 8, capacity, () -> {
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("nc_multiblock_sources", path);
    }
}
