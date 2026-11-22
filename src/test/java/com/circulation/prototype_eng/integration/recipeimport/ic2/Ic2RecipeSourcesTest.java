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
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        var captured = new AtomicReference<NBTTagCompound>();
        var imported = Ic2RecipeSources.convertAll(id("pool"), "compressor", List.of(source), (_, copiedMetadata) -> {
            captured.set(copiedMetadata);
            return true;
        }).getFirst();
        var resources = imported.requirements().get(RecipeRequirements.RESOURCES).getFirst();
        var actual = (ResourceRequirement) resources.payload();
        var input = actual.dynamicInputs().getFirst();
        assertEquals(source.getInput().getAmount(), input.amount());
        assertTrue(input.matcher().matches(AEItemKey.of(first), input.pattern()));
        assertTrue(input.matcher().matches(AEItemKey.of(alternate), input.pattern()));
        assertFalse(input.matcher().matches(AEItemKey.of(tagged(new ItemStack(Items.GOLD_INGOT), "rejected")), input.pattern()));
        assertFalse(input.matcher().matches(AEItemKey.of(new ItemStack(Items.DIAMOND)), input.pattern()));
        assertNotSame(metadata, captured.get());
        metadata.setString("machine", "changed");
        assertEquals("compressor", captured.get().getString("machine"));
    }

    @Test
    void consumesOneAcceptedAlternativeAndRetriesWithoutPayingIncompleteInput() {
        var first = tagged(new ItemStack(Items.IRON_INGOT), "accepted");
        var alternate = tagged(new ItemStack(Items.GOLD_INGOT), "accepted");
        var imported = Ic2RecipeSources.convert(id("execution"), "compressor", 0,
            source(List.of(first, alternate), 3, new NBTTagCompound()));
        var materialOnly = new RecipeBuilder(imported.id(), 0);
        for (var requirement : imported.orderedRequirements()) {
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
        var forwardMetadata = new Object2ObjectOpenHashMap<ResourceLocation, NBTTagCompound>();
        var reverseMetadata = new Object2ObjectOpenHashMap<ResourceLocation, NBTTagCompound>();
        var forward = Ic2RecipeSources.convertAll(id("stable"), "compressor", List.of(first, second, metadataVariant), (recipe, metadata) -> {
            forwardMetadata.put(recipe.id(), metadata);
            return true;
        });
        var reverse = Ic2RecipeSources.convertAll(id("stable"), "compressor", List.of(metadataVariant, second, first), (recipe, metadata) -> {
            reverseMetadata.put(recipe.id(), metadata);
            return true;
        });

        assertEquals(3, forward.size());
        assertEquals(forward.size(), reverse.size());
        for (int index = 0; index < forward.size(); index++) {
            var expected = forward.get(index);
            var actual = reverse.get(index);
            assertEquals(expected.id(), actual.id());
            var expectedResources = (ResourceRequirement) expected.requirements().get(RecipeRequirements.RESOURCES).getFirst().payload();
            var actualResources = (ResourceRequirement) actual.requirements().get(RecipeRequirements.RESOURCES).getFirst().payload();
            assertEquals(expectedResources.outputs(), actualResources.outputs());
            var expectedInput = expectedResources.dynamicInputs().getFirst();
            var actualInput = actualResources.dynamicInputs().getFirst();
            assertEquals(expectedInput.amount(), actualInput.amount());
            assertEquals(expectedInput.displayedCandidates(), actualInput.displayedCandidates());
            assertEquals(forwardMetadata.get(expected.id()), reverseMetadata.get(actual.id()));
        }
    }

    @Test
    void nullHandlerSkipsMetadataSnapshotsAndRejectedRecipesAreNotAdded() {
        var metadata = new NBTTagCompound() {
            @Override
            @Nonnull
            public NBTTagCompound copy() {
                throw new AssertionError("Metadata must not be copied without a creation handler");
            }
        };
        var source = source(List.of(tagged(new ItemStack(Items.IRON_INGOT), "accepted")), 1, metadata);
        assertEquals(1, Ic2RecipeSources.convertAll(id("no_handler"), "compressor", List.of(source)).size());
        var ordinary = source(List.of(tagged(new ItemStack(Items.IRON_INGOT), "accepted")), 1, new NBTTagCompound());
        assertTrue(Ic2RecipeSources.convertAll(id("rejected"), "compressor", List.of(ordinary), (_, _) -> false).isEmpty());
        var failure = new IllegalStateException("Creation handler failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> Ic2RecipeSources.convertAll(id("failed"), "compressor", List.of(ordinary), (_, _) -> {
                throw failure;
            })));
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
