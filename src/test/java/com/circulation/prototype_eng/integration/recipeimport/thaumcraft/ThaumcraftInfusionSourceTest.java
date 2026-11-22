package com.circulation.prototype_eng.integration.recipeimport.thaumcraft;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.InfusionRecipe;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThaumcraftInfusionSourceTest {
    @BeforeAll
    static void initializeMinecraft() {
        Bootstrap.register();
    }

    @Test
    void ordinaryInfusionKeepsMainAndEveryComponentWithAspectMetadata() {
        var aspects = new AspectList().add(Aspect.FIRE, 11).add(Aspect.WATER, 7);
        var source = new InfusionRecipe("IMPORT_RESEARCH", new ItemStack(Items.DIAMOND), 2, aspects,
            Ingredient.fromStacks(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)),
            new ItemStack(Items.REDSTONE), new ItemStack(Items.GLOWSTONE_DUST));
        var imported = ThaumcraftInfusionSource.convert(id("pool"), id("source"), source).getFirst();
        var resources = resources(imported.recipe().build());

        assertEquals(source.getComponents().size() + 1, resources.dynamicInputs().size());
        var main = resources.dynamicInputs().getFirst();
        assertTrue(main.matcher().matches(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), main.pattern()));
        assertTrue(main.matcher().matches(AEItemKey.of(new ItemStack(Items.GOLD_INGOT)), main.pattern()));
        assertFalse(main.matcher().matches(AEItemKey.of(new ItemStack(Items.COAL)), main.pattern()));
        assertSame(Items.DIAMOND, ((AEItemKey) resources.outputs().getFirst().what()).getItem());
        var context = imported.context();
        assertNotNull(context);
        assertSame(source, context.source());
        assertNotSame(aspects, context.aspects());
        for (var aspect : aspects.getAspects())
            assertEquals(aspects.getAmount(aspect), context.aspects().getAmount(aspect));
        assertEquals(source.getResearch(), context.research());
    }

    @Test
    void nbtOutputProducesOnlyTheCorrespondingMainInputVariant() {
        var source = new InfusionRecipe("IMPORT_NBT", new Object[]{"charge", new NBTTagInt(9)}, 1,
            new AspectList().add(Aspect.ENERGY, 3),
            Ingredient.fromStacks(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)),
            new ItemStack(Items.REDSTONE));
        var imported = ThaumcraftInfusionSource.convert(id("pool"), id("nbt_source"), source);

        assertEquals(source.getRecipeInput().getMatchingStacks().length, imported.size());
        for (var variant : imported) {
            var payload = resources(variant.recipe().build());
            assertEquals(1, payload.outputs().size());
            var input = (AEItemKey) payload.inputs().getFirst().what();
            var output = (AEItemKey) payload.outputs().getFirst().what();
            assertSame(input.getItem(), output.getItem());
            assertEquals(input.getReadOnlyStack().getMetadata(), output.getReadOnlyStack().getMetadata());
            var outputTag = output.getReadOnlyStack().getTagCompound();
            assertNotNull(outputTag);
            assertEquals(((NBTTagInt) ((Object[]) source.getRecipeOutput())[1]).getInt(), outputTag.getInteger("charge"));
            assertFalse(input.getReadOnlyStack().hasTagCompound());
        }
    }

    @Test
    void nbtOutputRejectsWildcardMetadataBeforeReturningAnyVariant() {
        var wildcard = new ItemStack(Items.DYE, 1, OreDictionary.WILDCARD_VALUE);
        var main = new Ingredient(new ItemStack[0]) {
            @Override
            @Nonnull
            public ItemStack[] getMatchingStacks() {
                return new ItemStack[]{new ItemStack(Items.IRON_INGOT), wildcard};
            }
        };
        var source = new InfusionRecipe("IMPORT_WILDCARD", new Object[]{"charge", new NBTTagInt(9)}, 1,
            new AspectList(), new ItemStack(Items.IRON_INGOT));
        source.sourceInput = main;

        assertThrows(IllegalArgumentException.class,
            () -> ThaumcraftInfusionSource.convert(id("pool"), id("wildcard_source"), source));
    }

    private static ResourceRequirement resources(MachineRecipe recipe) {
        for (var requirement : recipe.orderedRequirements()) {
            if (requirement.type() == RecipeRequirements.RESOURCES) return (ResourceRequirement) requirement.payload();
        }
        throw new AssertionError("Missing imported resource requirement");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("thaumcraft_import_test", path);
    }
}
