package com.circulation.prototype_eng.integration.recipeimport.ic2;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import ic2.api.recipe.ICannerBottleRecipeManager;
import ic2.api.recipe.ICannerEnrichRecipeManager;
import ic2.api.recipe.IElectrolyzerRecipeManager;
import ic2.api.recipe.IFermenterRecipeManager;
import ic2.api.recipe.IRecipeInput;
import ic2.core.ref.FluidName;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ic2AdditionalRecipeSourcesTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void washingRetainsRequiredWaterAlongsideItsActualItemPredicate() {
        var metadata = new NBTTagCompound();
        metadata.setInteger("amount", 750);
        var source = new ic2.api.recipe.MachineRecipe<IRecipeInput, Collection<ItemStack>>(
            input(new ItemStack(Items.IRON_INGOT), 3), List.of(new ItemStack(Items.DIAMOND, 2)), metadata);
        var recipe = Ic2RecipeSources.convertAll(id("washing"), "oreWashing", List.of(source), null, 17, 19, true).getFirst();
        var resources = resources(recipe);
        assertEquals(source.getMetaData().getInteger("amount"), resources.inputs().getFirst().amount());
        assertSame(FluidRegistry.WATER, ((AEFluidKey) resources.inputs().getFirst().what()).getFluid());
        var dynamic = resources.dynamicInputs().getFirst();
        assertEquals(source.getInput().getAmount(), dynamic.amount());
        assertTrue(dynamic.matcher().matches(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), dynamic.pattern()));
        assertFalse(dynamic.matcher().matches(AEItemKey.of(new ItemStack(Items.GOLD_INGOT)), dynamic.pattern()));
        metadata.setInteger("amount", 0);
        assertTrue(Ic2RecipeSources.convertAll(id("invalid_washing"), "oreWashing", List.of(source), null, 17, 19, true).isEmpty());
    }

    @Test
    void cannerKeepsBothInputsAndIndependentMetadataWithoutCallingNullHandlers() {
        var metadata = new NBTTagCompound() {
            @Override
            @Nonnull
            public NBTTagCompound copy() {
                throw new AssertionError("Null creation handler must not request metadata snapshots");
            }
        };
        var inputs = new ICannerBottleRecipeManager.Input(input(new ItemStack(Items.GLASS_BOTTLE), 2),
            input(new ItemStack(Items.APPLE), 4));
        var source = new ic2.api.recipe.MachineRecipe<>(inputs, new ItemStack(Items.GOLD_INGOT, 3), metadata);
        var bottle = Ic2CannerSources.bottles(id("bottle"), List.of(source), null).getFirst();
        var resources = resources(bottle);
        assertEquals(2, resources.dynamicInputs().size());
        assertEquals(inputs.container.getAmount(), resources.dynamicInputs().getFirst().amount());
        assertEquals(inputs.fill.getAmount(), resources.dynamicInputs().getLast().amount());
        assertEquals(source.getOutput().getCount(), resources.outputs().getFirst().amount());
        var enrichInput = new ICannerEnrichRecipeManager.Input(new FluidStack(FluidRegistry.WATER, 800), input(new ItemStack(Items.REDSTONE), 5));
        var enrichment = new ic2.api.recipe.MachineRecipe<>(enrichInput, new FluidStack(FluidRegistry.LAVA, 200), metadata);
        var enriched = resources(Ic2CannerSources.enrichments(id("enrich"), List.of(enrichment), null).getFirst());
        assertEquals(enrichInput.fluid.amount, enriched.inputs().getFirst().amount());
        assertEquals(enrichInput.additive.getAmount(), enriched.dynamicInputs().getFirst().amount());
        assertEquals(enrichment.getOutput().amount, enriched.outputs().getFirst().amount());

        var mutable = new NBTTagCompound();
        mutable.setString("value", "original");
        var ordinary = new ic2.api.recipe.MachineRecipe<>(inputs, source.getOutput(), mutable);
        var calls = new AtomicInteger();
        assertTrue(Ic2CannerSources.bottles(id("rejected"), List.of(ordinary), (_, copy) -> {
            calls.incrementAndGet();
            assertNotSame(mutable, copy);
            copy.setString("value", "edited");
            return false;
        }).isEmpty());
        assertEquals(1, calls.get());
        assertEquals("original", mutable.getString("value"));
        var failure = new IllegalStateException("Canner handler failed");
        var ordinaryEnrichment = new ic2.api.recipe.MachineRecipe<>(enrichInput, enrichment.getOutput(), mutable);
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> Ic2CannerSources.enrichments(id("failure"), List.of(ordinaryEnrichment), (_, _) -> {
                throw failure;
            })));
    }

    @Test
    void electrolysisKeepsEveryOutputAndItsSourceEnergyDurationAndDirections() {
        var source = new IElectrolyzerRecipeManager.ElectrolyzerRecipe(400, 7, 23,
            new IElectrolyzerRecipeManager.ElectrolyzerOutput(FluidRegistry.LAVA.getName(), 50, EnumFacing.WEST),
            new IElectrolyzerRecipeManager.ElectrolyzerOutput(FluidRegistry.WATER.getName(), 80, EnumFacing.UP));
        var converted = Ic2FluidRecipeSources.electrolyzer(id("electrolysis"), Map.of(FluidRegistry.WATER.getName(), source),
            (recipe, eu, directions) -> {
                assertEquals(source.EUaTick, eu);
                assertArrayEquals(new EnumFacing[]{source.outputs[0].tankDirection, source.outputs[1].tankDirection}, directions);
                directions[0] = EnumFacing.EAST;
                recipe.durationTicks(recipe.durationTicks() + 1);
                return true;
            }).getFirst();
        assertEquals(source.ticksNeeded + 1, converted.durationTicks());
        var materials = resources(converted);
        assertEquals(source.inputAmount, materials.inputs().getFirst().amount());
        assertEquals(source.outputs.length, materials.outputs().size());
        for (int index = 0; index < source.outputs.length; index++) {
            assertEquals(source.outputs[index].fluidAmount, materials.outputs().get(index).amount());
            assertSame(source.outputs[index].getOutput().getFluid(), ((AEFluidKey) materials.outputs().get(index).what()).getFluid());
        }
        var energy = (EnergyRequirement) converted.requirements().get(RecipeRequirements.ENERGY).getFirst().payload();
        assertEquals((long) source.EUaTick * 4, energy.inputPerTick());
        assertSame(EnumFacing.WEST, source.outputs[0].tankDirection);
        assertTrue(Ic2FluidRecipeSources.electrolyzer(id("rejected_electrolysis"), Map.of(FluidRegistry.WATER.getName(), source),
            (_, _, _) -> false).isEmpty());
    }

    @Test
    void fermentationNullHandlerDoesNotReadMachineConfigOrCreateFixedFertilizerByproducts() {
        var source = new IFermenterRecipeManager.FermentationProperty(25, 37, FluidRegistry.LAVA.getName(), 90);
        var converted = Ic2FluidRecipeSources.fermenter(id("fermenter"), Map.of(FluidRegistry.WATER.getName(), source), null).getFirst();
        var materials = resources(converted);
        assertEquals(source.inputAmount, materials.inputs().getFirst().amount());
        assertEquals(source.outputAmount, materials.outputs().getFirst().amount());
        assertSame(source.getOutput().getFluid(), ((AEFluidKey) materials.outputs().getFirst().what()).getFluid());
        assertTrue(materials.outputs().stream().allMatch(stack -> stack.what() instanceof AEFluidKey));
    }

    @Test
    void blastFurnaceRetainsPerTickAirAndMetadataDefinedDuration() {
        if (!FluidName.air.hasInstance()) {
            var air = new Fluid("pe_ic2_import_air", id("air_still"), id("air_flow"));
            FluidRegistry.registerFluid(air);
            FluidName.air.setInstance(air);
        }
        var metadata = new NBTTagCompound();
        metadata.setInteger("duration", 31);
        metadata.setInteger("fluid", 6);
        var source = new ic2.api.recipe.MachineRecipe<IRecipeInput, Collection<ItemStack>>(
            input(new ItemStack(Items.IRON_INGOT), 2), List.of(new ItemStack(Items.GOLD_INGOT)), metadata);
        var converted = Ic2BlastFurnaceSource.convertAll(id("blast"), List.of(source), null).getFirst();
        assertEquals(metadata.getInteger("duration"), converted.durationTicks());
        var materials = resources(converted);
        assertEquals(metadata.getInteger("fluid"), materials.inputsPerTick().getFirst().amount());
        assertSame(FluidName.air.getInstance(), ((AEFluidKey) materials.inputsPerTick().getFirst().what()).getFluid());
        assertEquals(source.getInput().getAmount(), materials.dynamicInputs().getFirst().amount());
    }

    private static IRecipeInput input(ItemStack accepted, int amount) {
        return new IRecipeInput() {
            @Override
            public boolean matches(ItemStack stack) {
                return stack.getCount() >= amount && stack.isItemEqual(accepted);
            }

            @Override
            public int getAmount() {
                return amount;
            }

            @Override
            public List<ItemStack> getInputs() {
                return List.of(accepted);
            }
        };
    }

    private static ResourceRequirement resources(MachineRecipe recipe) {
        return (ResourceRequirement) Objects.requireNonNull(recipe.requirements().get(RecipeRequirements.RESOURCES)).getFirst().payload();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("ic2_additional_test", path);
    }
}
