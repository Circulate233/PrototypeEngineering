package com.circulation.prototype_eng.integration.recipeimport.ic2;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipe;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ic2RecipeSourcesTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void preservesDeclaredAmountFullInputPredicateAndIndependentMetadata() {
        var first = tagged(new ItemStack(Items.IRON_INGOT), "accepted");
        var alternate = tagged(new ItemStack(Items.GOLD_INGOT), "accepted");
        var metadata = new NBTTagCompound();
        metadata.setString("machine", "compressor");
        var source = source(List.of(first, alternate), 5, metadata);
        var imported = Ic2RecipeSources.convert(id("pool"), "compressor", 0, source);
        var resources = imported.recipe().build().requirements().get(RecipeRequirements.RESOURCES).getFirst();
        var actual = (ResourceRequirement) resources.payload();
        var input = actual.dynamicInputs().getFirst();
        assertEquals(source.getInput().getAmount(), input.amount());
        assertTrue(input.matcher().matches(AEItemKey.of(first), input.pattern()));
        assertTrue(input.matcher().matches(AEItemKey.of(alternate), input.pattern()));
        assertFalse(input.matcher().matches(AEItemKey.of(tagged(new ItemStack(Items.GOLD_INGOT), "rejected")), input.pattern()));
        assertFalse(input.matcher().matches(AEItemKey.of(new ItemStack(Items.DIAMOND)), input.pattern()));
        assertSame(source, imported.context().source());
        assertNotSame(metadata, imported.context().metadata());
        metadata.setString("machine", "changed");
        assertEquals("compressor", imported.context().metadata().getString("machine"));
    }

    @Test
    void consumesOneAcceptedAlternativeAndRetriesWithoutPayingIncompleteInput() {
        var first = tagged(new ItemStack(Items.IRON_INGOT), "accepted");
        var alternate = tagged(new ItemStack(Items.GOLD_INGOT), "accepted");
        var imported = Ic2RecipeSources.convert(id("execution"), "compressor", 0,
            source(List.of(first, alternate), 3, new NBTTagCompound()));
        var materialOnly = new RecipeBuilder(imported.recipe().id(), 0);
        for (var requirement : imported.recipe().requirements()) {
            if (requirement.type() == RecipeRequirements.RESOURCES) materialOnly.addRequirement(requirement);
        }
        var input = hatch(HatchMode.INPUT);
        var output = hatch(HatchMode.OUTPUT);
        var materials = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        var key = Objects.requireNonNull(AEItemKey.of(alternate));
        materials.insert(key, 2, Actionable.MODULATE);
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(input, output), List.of(), List.of()));
        var process = new RecipeProcess(owner, materialOnly.build(), 1);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(2, materials.availableAmount(key));
        materials.insert(key, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, materials.amount(key));
        assertEquals(2, products.amount(AEItemKey.of(new ItemStack(Items.DIAMOND))));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
    }

    @Test
    void unorderedSourceEnumerationKeepsIdsBoundToTheSameMaterialsAndMetadata() {
        var firstMetadata = new NBTTagCompound();
        firstMetadata.setString("variant", "first");
        var secondMetadata = new NBTTagCompound();
        secondMetadata.setString("variant", "second");
        var first = source(List.of(tagged(new ItemStack(Items.IRON_INGOT), "accepted")), 3, firstMetadata);
        var second = source(List.of(tagged(new ItemStack(Items.GOLD_INGOT), "accepted")), 5, firstMetadata);
        var metadataVariant = source(List.of(tagged(new ItemStack(Items.IRON_INGOT), "accepted")), 3, secondMetadata);
        var forward = Ic2RecipeSources.convertAll(id("stable"), "compressor", List.of(first, second, metadataVariant));
        var reverse = Ic2RecipeSources.convertAll(id("stable"), "compressor", List.of(metadataVariant, second, first));

        assertEquals(3, forward.size());
        assertEquals(forward.size(), reverse.size());
        for (int index = 0; index < forward.size(); index++) {
            var expected = forward.get(index);
            var actual = reverse.get(index);
            assertEquals(expected.recipe().id(), actual.recipe().id());
            var expectedResources = (ResourceRequirement) expected.recipe().build().requirements().get(RecipeRequirements.RESOURCES).getFirst().payload();
            var actualResources = (ResourceRequirement) actual.recipe().build().requirements().get(RecipeRequirements.RESOURCES).getFirst().payload();
            assertEquals(expectedResources.outputs(), actualResources.outputs());
            var expectedInput = expectedResources.dynamicInputs().getFirst();
            var actualInput = actualResources.dynamicInputs().getFirst();
            assertEquals(expectedInput.amount(), actualInput.amount());
            assertEquals(expectedInput.displayedCandidates(), actualInput.displayedCandidates());
            assertEquals(Objects.requireNonNull(expected.context()).metadata(), Objects.requireNonNull(actual.context()).metadata());
        }
    }

    private static MachineRecipe<IRecipeInput, Collection<ItemStack>> source(List<ItemStack> candidates, int amount,
                                                                             NBTTagCompound metadata) {
        var input = new IRecipeInput() {
            @Override
            public boolean matches(ItemStack stack) {
                if (stack.getCount() < amount || !stack.hasTagCompound()
                    || !"accepted".equals(Objects.requireNonNull(stack.getTagCompound()).getString("grade")))
                    return false;
                for (var candidate : candidates) if (candidate.isItemEqual(stack)) return true;
                return false;
            }

            @Override
            public int getAmount() {
                return amount;
            }

            @Override
            public List<ItemStack> getInputs() {
                return candidates;
            }
        };
        return new MachineRecipe<>(input, List.of(new ItemStack(Items.DIAMOND, 2)), metadata);
    }

    private static ItemStack tagged(ItemStack stack, String value) {
        var tag = new NBTTagCompound();
        tag.setString("grade", value);
        stack.setTagCompound(tag);
        return stack;
    }

    private static BufferedResourceHatch hatch(HatchMode mode) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(AEKeyType.items(), 64);
        return new BufferedResourceHatch(mode, 2, capacities, () -> {
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("ic2_import_test", path);
    }
}
