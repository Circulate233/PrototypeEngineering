package com.circulation.prototype_eng.integration.recipeimport.thermalexpansion;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import cofh.thermalexpansion.util.managers.machine.InsolatorManager;
import cofh.thermalfoundation.item.ItemFertilizer;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThermalInsolatorSourceTest {
    @BeforeAll
    static void initializeMinecraftAndFertilizerRegistry() {
        Bootstrap.register();
        var basic = ItemFertilizer.fertilizerBasic;
        var rich = ItemFertilizer.fertilizerRich;
        var flux = ItemFertilizer.fertilizerFlux;
        try {
            ItemFertilizer.fertilizerBasic = new ItemStack(Items.DYE, 1, 15);
            ItemFertilizer.fertilizerRich = new ItemStack(Items.BONE);
            ItemFertilizer.fertilizerFlux = new ItemStack(Items.BLAZE_POWDER);
            InsolatorManager.preInit();
        } finally {
            ItemFertilizer.fertilizerBasic = basic;
            ItemFertilizer.fertilizerRich = rich;
            ItemFertilizer.fertilizerFlux = flux;
        }
    }

    @Test
    void plantRecipeConsumesFertilizerAndWaterWhileKeepingIndependentSecondaryChance() {
        verify(InsolatorManager.Type.STANDARD, new ItemStack(Items.WHEAT_SEEDS), new ItemStack(Items.DYE, 2, 15));
    }

    @Test
    void treeRecipeKeepsBothInputsAndExactTotalEnergy() {
        verify(InsolatorManager.Type.TREE, new ItemStack(Items.APPLE), new ItemStack(Items.BONE, 3));
    }

    @Test
    void unorderedSourceEnumerationKeepsIdsBoundToTheSameInputsOutputsAndEnergy() {
        var firstPlant = new ItemStack(Items.WHEAT_SEEDS);
        var firstFertilizer = new ItemStack(Items.DYE, 2, 15);
        var secondPlant = new ItemStack(Items.BEETROOT_SEEDS);
        var secondFertilizer = new ItemStack(Items.BONE, 3);
        var first = InsolatorManager.addRecipe(101, 37, firstPlant, firstFertilizer,
            new ItemStack(Items.WHEAT, 4), new ItemStack(Items.STICK, 2), 35, InsolatorManager.Type.STANDARD);
        var second = InsolatorManager.addRecipe(203, 63, secondPlant, secondFertilizer,
            new ItemStack(Items.BEETROOT, 6), new ItemStack(Items.BEETROOT_SEEDS), 17, InsolatorManager.Type.STANDARD);
        assertNotNull(first);
        assertNotNull(second);
        try {
            var poolId = new ResourceLocation("thermal_import_test", "stable");
            var forward = ThermalInsolatorSource.convertAll(poolId, false, List.of(first, second));
            var reverse = ThermalInsolatorSource.convertAll(poolId, false, List.of(second, first));
            assertEquals(2, forward.size());
            assertEquals(forward.size(), reverse.size());
            for (int index = 0; index < forward.size(); index++) {
                var expected = forward.get(index);
                var actual = reverse.get(index);
                assertEquals(expected.id(), actual.id());
                assertEquals(expected.orderedRequirements().size(), actual.orderedRequirements().size());
                for (int requirementIndex = 0; requirementIndex < expected.orderedRequirements().size(); requirementIndex++) {
                    var expectedRequirement = expected.orderedRequirements().get(requirementIndex);
                    var actualRequirement = actual.orderedRequirements().get(requirementIndex);
                    assertSame(expectedRequirement.type(), actualRequirement.type());
                    if (expectedRequirement.payload() instanceof ResourceRequirement expectedResources) {
                        var actualResources = (ResourceRequirement) actualRequirement.payload();
                        assertEquals(expectedResources.inputs(), actualResources.inputs());
                        assertEquals(expectedResources.outputs(), actualResources.outputs());
                        assertEquals(expectedResources.chance(), actualResources.chance());
                        assertEquals(expectedResources.dynamicInputs().size(), actualResources.dynamicInputs().size());
                        for (int inputIndex = 0; inputIndex < expectedResources.dynamicInputs().size(); inputIndex++) {
                            var expectedInput = expectedResources.dynamicInputs().get(inputIndex);
                            var actualInput = actualResources.dynamicInputs().get(inputIndex);
                            assertEquals(expectedInput.amount(), actualInput.amount());
                            assertEquals(expectedInput.displayedCandidates(), actualInput.displayedCandidates());
                        }
                    } else {
                        assertEquals(expectedRequirement.payload(), actualRequirement.payload());
                    }
                }
            }
        } finally {
            InsolatorManager.removeRecipe(firstPlant, firstFertilizer);
            InsolatorManager.removeRecipe(secondPlant, secondFertilizer);
        }
    }

    @Test
    void creationHandlerCanRejectRecipesAndPropagatesFailure() {
        var plant = new ItemStack(Items.PUMPKIN_SEEDS);
        var fertilizer = new ItemStack(Items.DYE, 2, 15);
        var source = InsolatorManager.addRecipe(101, 37, plant, fertilizer,
            new ItemStack(Items.PUMPKIN_SEEDS, 4), new ItemStack(Items.STICK, 2), 35, InsolatorManager.Type.STANDARD);
        assertNotNull(source);
        try {
            var id = new ResourceLocation("thermal_import_test", "handler");
            assertTrue(ThermalInsolatorSource.convertAll(id, false, List.of(source), (_, _, _, _, _) -> false).isEmpty());
            var failure = new IllegalStateException("Creation handler failed");
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ThermalInsolatorSource.convertAll(id, false, List.of(source), (_, _, _, _, _) -> {
                    throw failure;
                })));
        } finally {
            InsolatorManager.removeRecipe(plant, fertilizer);
        }
    }

    private static void verify(InsolatorManager.Type type, ItemStack plant, ItemStack fertilizer) {
        var source = InsolatorManager.addRecipe(101, 37, plant, fertilizer,
            new ItemStack(Items.WHEAT, 4), new ItemStack(Items.STICK, 2), 35, type);
        assertNotNull(source);
        try {
            var id = new ResourceLocation("thermal_import_test", type.name().toLowerCase(Locale.ROOT));
            var recipe = ThermalInsolatorSource.convertAll(id, type == InsolatorManager.Type.TREE, List.of(source),
                (_, sourceEnergy, water, chance, hasFertilizer) -> {
                    assertEquals(source.getEnergy(), sourceEnergy);
                    assertEquals(source.getWater(), water);
                    assertEquals(source.getSecondaryOutputChance(), chance);
                    assertEquals(source.hasFertilizer(), hasFertilizer);
                    return true;
                }).getFirst();
            ResourceRequirement ordinary = null;
            ResourceRequirement secondary = null;
            EnergyRequirement energy = null;
            for (var requirement : recipe.orderedRequirements()) {
                if (requirement.type() == RecipeRequirements.RESOURCES) {
                    var resources = (ResourceRequirement) requirement.payload();
                    if (resources.dynamicInputs().isEmpty()) secondary = resources;
                    else ordinary = resources;
                } else if (requirement.type() == RecipeRequirements.ENERGY) {
                    energy = (EnergyRequirement) requirement.payload();
                }
            }
            assertNotNull(ordinary);
            assertNotNull(secondary);
            assertNotNull(energy);
            assertTrue(source.hasFertilizer());
            assertEquals(2, ordinary.dynamicInputs().size());
            var primaryInput = ordinary.dynamicInputs().getFirst();
            var secondaryInput = ordinary.dynamicInputs().getLast();
            assertTrue(primaryInput.matcher().matches(AEItemKey.of(source.getPrimaryInput()), primaryInput.pattern()));
            assertTrue(secondaryInput.matcher().matches(AEItemKey.of(source.getSecondaryInput()), secondaryInput.pattern()));
            assertFalse(primaryInput.matcher().matches(AEItemKey.of(new ItemStack(Items.DIAMOND)), primaryInput.pattern()));
            assertEquals(source.getPrimaryInput().getCount(), primaryInput.amount());
            assertEquals(source.getSecondaryInput().getCount(), secondaryInput.amount());
            assertSame(FluidRegistry.WATER, ((AEFluidKey) ordinary.inputs().getFirst().what()).getFluid());
            assertEquals(source.getWater(), ordinary.inputs().getFirst().amount());
            assertSame(source.getPrimaryOutput().getItem(), ((AEItemKey) ordinary.outputs().getFirst().what()).getItem());
            assertEquals(source.getPrimaryOutput().getCount(), ordinary.outputs().getFirst().amount());
            assertSame(source.getSecondaryOutput().getItem(), ((AEItemKey) secondary.outputs().getFirst().what()).getItem());
            assertEquals(source.getSecondaryOutputChance(), secondary.chance().percent());
            assertEquals(source.getEnergy(), energy.input());
            assertEquals(0, energy.inputPerTick());
        } finally {
            InsolatorManager.removeRecipe(plant, fertilizer);
        }
    }
}
