package com.circulation.prototype_eng.integration.recipeimport.tconstruct;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import slimeknights.mantle.util.RecipeMatch;
import slimeknights.tconstruct.library.smeltery.AlloyRecipe;
import slimeknights.tconstruct.library.smeltery.MeltingRecipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TConstructRecipeSourcesTest {
    @BeforeAll
    static void initializeMinecraft() {
        Bootstrap.register();
    }

    @Test
    void meltingConsumesMatchInputQuantityAndRetainsItsActualPredicate() {
        var source = new MeltingRecipe(RecipeMatch.of(new ItemStack(Items.IRON_INGOT), 144, 3),
            new FluidStack(FluidRegistry.WATER, 1), 750);
        var imported = TConstructMeltingSource.convert(id("melting"), source);
        var payload = resources(imported.recipe().build());
        var input = payload.dynamicInputs().getFirst();

        assertEquals(source.input.amountNeeded, input.amount());
        assertEquals(source.getResult().amount, payload.outputs().getFirst().amount());
        assertSame(source.getResult().getFluid(), ((AEFluidKey) payload.outputs().getFirst().what()).getFluid());
        assertTrue(input.matcher().matches(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), input.pattern()));
        assertFalse(input.matcher().matches(AEItemKey.of(new ItemStack(Items.GOLD_INGOT)), input.pattern()));
        var context = imported.context();
        assertNotNull(context);
        assertSame(source, context.source());

        var taggedIron = new ItemStack(Items.IRON_INGOT);
        taggedIron.setTagInfo("import_test", new NBTTagString("accepted"));
        assertEquals(source.matches(copyWithCount(taggedIron, source.input.amountNeeded)),
            input.matcher().matches(AEItemKey.of(taggedIron), input.pattern()));
    }

    @Test
    void alloyRetainsEveryFluidAndItsResultWithoutMutatingTheSource() {
        var alloy = new Fluid("pe_import_test_alloy", id("fluid_still"), id("fluid_flow"));
        FluidRegistry.registerFluid(alloy);
        var source = new AlloyRecipe(new FluidStack(alloy, 600),
            new FluidStack(FluidRegistry.WATER, 200), new FluidStack(FluidRegistry.LAVA, 400));
        var imported = TConstructAlloySource.convert(id("alloy"), source);
        var payload = resources(imported.recipe().build());

        assertEquals(source.getFluids().size(), payload.inputs().size());
        for (int index = 0; index < source.getFluids().size(); index++) {
            assertSame(source.getFluids().get(index).getFluid(), ((AEFluidKey) payload.inputs().get(index).what()).getFluid());
            assertEquals(source.getFluids().get(index).amount, payload.inputs().get(index).amount());
        }
        assertSame(alloy, ((AEFluidKey) payload.outputs().getFirst().what()).getFluid());
        assertEquals(source.getResult().amount, payload.outputs().getFirst().amount());
        var context = imported.context();
        assertNotNull(context);
        assertSame(source, context.source());
    }

    private static ItemStack copyWithCount(ItemStack stack, int count) {
        var copy = stack.copy();
        copy.setCount(count);
        return copy;
    }

    private static ResourceRequirement resources(MachineRecipe recipe) {
        for (var requirement : recipe.orderedRequirements()) {
            if (requirement.type() == RecipeRequirements.RESOURCES) return (ResourceRequirement) requirement.payload();
        }
        throw new AssertionError("Missing imported resource requirement");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("tconstruct_import_test", path);
    }
}
