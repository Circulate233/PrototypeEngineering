package com.circulation.prototype_eng.api.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeBuilderEditingTest {
    private static final KeyType TYPE = new KeyType("recipe_builder_editing");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key ALTERNATE = new Key(TYPE, "alternate");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final Key BYPRODUCT = new Key(TYPE, "byproduct");

    @Test
    void appendingResourcesPreservesComplexPayloadPoliciesAndIndependentSnapshots() {
        var binding = id("source/resources");
        var source = complexResources();
        var original = new RecipeBuilder(id("imported/variant"), 3)
            .addRequirement(RecipeRequirements.RESOURCES.requirement(binding, source)).build();
        var builder = RecipeBuilder.from(original);
        var addedInput = stack(ALTERNATE, 41);
        var addedOutput = stack(BYPRODUCT, 43);
        var addedTickInput = stack(ALTERNATE, 47);
        var addedTickOutput = stack(BYPRODUCT, 53);

        assertSame(builder, builder.addInput(binding, addedInput));
        var firstSnapshot = builder.build();
        assertSame(builder, builder.addOutput(binding, addedOutput));
        assertSame(builder, builder.addInputPerTick(binding, addedTickInput));
        assertSame(builder, builder.addOutputPerTick(binding, addedTickOutput));
        var edited = builder.resources(binding);

        assertEquals(List.of(source.inputs().getFirst(), source.inputs().getLast(), addedInput), edited.inputs());
        assertEquals(List.of(source.outputs().getFirst(), source.outputs().getLast(), addedOutput), edited.outputs());
        assertEquals(List.of(source.inputsPerTick().getFirst(), addedTickInput), edited.inputsPerTick());
        assertEquals(List.of(source.outputsPerTick().getFirst(), addedTickOutput), edited.outputsPerTick());
        assertPolicies(source, edited);
        assertSame(source.randomOutputs(), edited.randomOutputs());
        assertEquals(source.fuzzyInputs(), edited.fuzzyInputs());
        assertEquals(source.dynamicInputs(), edited.dynamicInputs());
        assertSame(source, resources(original, binding));
        assertEquals(source.outputs(), resources(firstSnapshot, binding).outputs());
        assertEquals(source.inputsPerTick(), resources(firstSnapshot, binding).inputsPerTick());
        assertEquals(source.outputsPerTick(), resources(firstSnapshot, binding).outputsPerTick());
        assertNotSame(requirement(original, binding), builder.requirement(binding));
        assertNotSame(requirement(firstSnapshot, binding), builder.requirement(binding));
        assertCanonical(builder, binding);
    }

    @Test
    void indexedRemovalTreatsAlternativeInputsAsWholeGroupsAndRandomOutputsAsCandidates() {
        var binding = id("grouped_io");
        var source = complexResources();
        var builder = new RecipeBuilder(id("grouped"), 2)
            .addRequirement(RecipeRequirements.RESOURCES.requirement(binding, source));
        var firstDynamic = source.dynamicInputs().getFirst();
        var lastDynamic = source.dynamicInputs().getLast();

        builder.removeInput(binding, source.inputs().size() + source.fuzzyInputs().size());
        assertEquals(List.of(lastDynamic), builder.resources(binding).dynamicInputs());
        assertEquals(source.fuzzyInputs(), builder.resources(binding).fuzzyInputs());
        assertNotSame(firstDynamic, builder.resources(binding).dynamicInputs().getFirst());
        builder.removeInput(binding, source.inputs().size());
        assertTrue(builder.resources(binding).fuzzyInputs().isEmpty());
        assertSame(lastDynamic, builder.resources(binding).dynamicInputs().getFirst());
        builder.removeInput(binding, 0);
        assertEquals(List.of(source.inputs().getLast()), builder.resources(binding).inputs());

        builder.removeOutput(binding, source.outputs().size());
        var remainingPool = Objects.requireNonNull(builder.resources(binding).randomOutputs());
        var sourcePool = Objects.requireNonNull(source.randomOutputs());
        assertEquals(List.of(sourcePool.candidates().getLast()), remainingPool.candidates());
        assertSame(sourcePool.candidates().getLast(), remainingPool.candidates().getFirst());
        assertEquals(sourcePool.pickCount(), remainingPool.pickCount());
        builder.removeOutput(binding, source.outputs().size());
        var emptyPool = Objects.requireNonNull(builder.resources(binding).randomOutputs());
        assertTrue(emptyPool.candidates().isEmpty());
        assertEquals(sourcePool.pickCount(), emptyPool.pickCount());
        builder.removeOutput(binding, 0);
        assertEquals(List.of(source.outputs().getLast()), builder.resources(binding).outputs());
        builder.removeInputPerTick(binding, 0).removeOutputPerTick(binding, 0);
        assertTrue(builder.resources(binding).inputsPerTick().isEmpty());
        assertTrue(builder.resources(binding).outputsPerTick().isEmpty());
        assertPolicies(source, builder.resources(binding));
        assertEquals(2, source.dynamicInputs().size());
        assertEquals(2, sourcePool.candidates().size());
        assertCanonical(builder, binding);
    }

    @Test
    void clearingSelectedBindingsKeepsOtherSidesOtherIdsAndNestedDefinitions() {
        var firstId = id("source/first_resources");
        var secondId = id("source/second_resources");
        var energyId = id("source/energy");
        var first = complexResources();
        var second = complexResources();
        var child = RecipeRequirements.RESOURCES.requirement(id("child/resources"), complexResources());
        var selective = RecipeRequirements.SELECTIVE.requirement(id("alternatives"), new SelectiveRequirement(List.of(child)));
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(1, child));
        var energy = RecipeRequirements.ENERGY.requirement(energyId, new EnergyRequirement(5, 7, 11, 13, true, InputAllocation.SINGLE_SOURCE));
        var builder = new RecipeBuilder(id("variant"), 2)
            .addRequirement(RecipeRequirements.RESOURCES.requirement(firstId, first))
            .addRequirement(RecipeRequirements.RESOURCES.requirement(secondId, second))
            .addRequirement(energy).addRequirement(selective).addRequirement(checkpoint);

        assertSame(builder, builder.clearInputs(firstId));
        assertInputsEmpty(builder.resources(firstId));
        assertEquals(first.outputs(), builder.resources(firstId).outputs());
        assertSame(first.randomOutputs(), builder.resources(firstId).randomOutputs());
        assertSame(second, builder.resources(secondId));
        assertSame(builder, builder.clearOutputs(secondId));
        assertOutputsEmpty(builder.resources(secondId));
        assertEquals(second.inputs(), builder.resources(secondId).inputs());
        assertEquals(second.dynamicInputs(), builder.resources(secondId).dynamicInputs());
        assertEquals(second.inputsPerTick(), builder.resources(secondId).inputsPerTick());

        assertSame(builder, builder.clearInputs());
        assertInputsEmpty(builder.resources(secondId));
        assertEquals(first.outputs(), builder.resources(firstId).outputs());
        assertSame(builder, builder.clearOutputs());
        assertOutputsEmpty(builder.resources(firstId));
        assertOutputsEmpty(builder.resources(secondId));
        assertPolicies(first, builder.resources(firstId));
        assertPolicies(second, builder.resources(secondId));
        assertSame(energy, builder.requirement(energyId));
        assertSame(selective, builder.requirement(selective.id()));
        assertSame(checkpoint, builder.requirement(checkpoint.id()));
        assertFalse(child.payload().inputs().isEmpty());
        assertFalse(child.payload().outputs().isEmpty());
        assertNotNull(child.payload().randomOutputs());
        assertCanonical(builder, firstId);
        assertCanonical(builder, secondId);
    }

    @Test
    void energyEditingPreservesOutputPolicyAllocationAndPreviouslyBuiltDefinition() {
        var binding = id("thermal/energy/1");
        var source = new EnergyRequirement(101, 0, 0, 0, true, InputAllocation.SINGLE_SOURCE);
        var original = new RecipeBuilder(id("thermal/import"), 6)
            .addRequirement(RecipeRequirements.ENERGY.requirement(binding, source)).build();
        var builder = RecipeBuilder.from(original);

        assertSame(builder, builder.energy(binding, 107, 109, 113, 127));
        var edited = builder.energy(binding);
        assertEquals(107, edited.input());
        assertEquals(109, edited.inputPerTick());
        assertEquals(113, edited.outputPerTick());
        assertEquals(127, edited.output());
        assertEquals(source.voidExcessOutputs(), edited.voidExcessOutputs());
        assertSame(source.inputAllocation(), edited.inputAllocation());
        assertSame(source, requirement(original, binding).payload());
        assertCanonical(builder, binding);
    }

    @Test
    void explicitReplacementKeepsItsPositionAndRemovalKeepsNeighborDefinitions() {
        var first = RecipeRequirements.RESOURCES.requirement(id("first"), complexResources());
        var middle = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(5, 0, 0, 0, false));
        var last = RecipeRequirements.RESOURCES.requirement(id("last"), complexResources());
        var builder = new RecipeBuilder(id("replacement"), 2).addRequirement(first).addRequirement(middle).addRequirement(last);
        var snapshot = builder.build();
        var replacement = first.withPayload(new ResourceRequirement(List.of(stack(ALTERNATE, 2)), List.of(stack(BYPRODUCT, 3))));

        assertSame(builder, builder.replaceRequirement(replacement));
        assertEquals(first.id(), builder.requirements().getFirst().id());
        assertSame(replacement.payload(), builder.resources(first.id()));
        assertCanonical(builder, first.id());
        assertSame(middle, builder.requirements().get(1));
        assertSame(last, builder.requirements().getLast());
        assertSame(first, requirement(snapshot, first.id()));
        var changedType = RecipeRequirements.ENERGY.requirement(first.id(), new EnergyRequirement(17, 0, 0, 0, false));
        assertSame(builder, builder.replaceRequirement(changedType));
        assertSame(changedType, builder.requirement(first.id()));
        assertSame(changedType.payload(), builder.energy(first.id()));
        assertSame(builder, builder.removeRequirement(middle.id()));
        assertEquals(2, builder.requirements().size());
        assertSame(last, builder.requirements().getLast());
        assertSame(middle, requirement(snapshot, middle.id()));
    }

    @Test
    void invalidIdsTypesIndicesAndEnergyNeverPartiallyChangeDefinitions() {
        var io = id("io");
        var energy = id("energy");
        var missing = id("missing");
        var builder = new RecipeBuilder(id("invalid"), 2)
            .addRequirement(RecipeRequirements.RESOURCES.requirement(io, complexResources()))
            .addRequirement(RecipeRequirements.ENERGY.requirement(energy, new EnergyRequirement(5, 0, 0, 0, false)));

        assertRejected(IllegalArgumentException.class, builder, () -> builder.requirement(missing));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.resources(energy));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.energy(io));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.removeRequirement(missing));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.replaceRequirement(
            RecipeRequirements.RESOURCES.requirement(missing, complexResources())));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.addInput(energy, stack(MATERIAL, 1)));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.energy(io, 1, 0, 0, 0));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.energy(missing, -1, 0, 0, 0));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.clearInputs(missing));
        assertRejected(IllegalArgumentException.class, builder, () -> builder.clearOutputs(energy));
        assertRejected(IndexOutOfBoundsException.class, builder, () -> builder.removeInput(io, -1));
        assertRejected(IndexOutOfBoundsException.class, builder, () -> builder.removeInput(io, Integer.MAX_VALUE));
        assertRejected(IndexOutOfBoundsException.class, builder, () -> builder.removeOutput(io, Integer.MAX_VALUE));
        assertRejected(IndexOutOfBoundsException.class, builder, () -> builder.removeInputPerTick(io, Integer.MAX_VALUE));
        assertRejected(IndexOutOfBoundsException.class, builder, () -> builder.removeOutputPerTick(io, Integer.MAX_VALUE));
    }

    @Test
    void newlyCreatedStageBindingsExecuteAtomicallyAndResumeWithEditedDefinitions() {
        var startId = id("start");
        var endId = id("end");
        var tickInputId = id("tick_input");
        var tickOutputId = id("tick_output");
        var energyId = id("energy");
        var builder = new RecipeBuilder(id("execution"), 2)
            .addInput(startId, stack(MATERIAL, 99))
            .addOutput(endId, stack(PRODUCT, 99))
            .addInputPerTick(tickInputId, stack(MATERIAL, 99))
            .addOutputPerTick(tickOutputId, stack(PRODUCT, 99))
            .energy(energyId, 99, 0, 0, 0);
        var unedited = builder.build();
        builder.removeInput(startId, 0).addInput(startId, stack(MATERIAL, 2))
            .removeOutput(endId, 0).addOutput(endId, stack(PRODUCT, 5))
            .removeInputPerTick(tickInputId, 0).addInputPerTick(tickInputId, stack(MATERIAL, 3))
            .removeOutputPerTick(tickOutputId, 0).addOutputPerTick(tickOutputId, stack(PRODUCT, 1))
            .energy(energyId, 7, 11, 13, 17);
        var recipe = builder.build();
        for (var binding : recipe.orderedRequirements()) assertSame(binding, binding.definition());
        assertEquals(99, resources(unedited, startId).inputs().getFirst().amount());
        assertEquals(99, resources(unedited, endId).outputs().getFirst().amount());
        var input = hatch(HatchMode.INPUT);
        var output = hatch(HatchMode.OUTPUT);
        var materials = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        var consumed = new EnergyBuffer(1000, () -> {
        });
        var generated = new EnergyBuffer(1000, () -> {
        });
        materials.insert(MATERIAL, 8, Actionable.MODULATE);
        consumed.receiveEnergy(6, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(input, output), List.of(consumed), List.of(generated)));
        var process = new RecipeProcess(machine, recipe, 17);

        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertEquals(8, materials.availableAmount(MATERIAL));
        assertEquals(6, consumed.storedEnergy());
        consumed.receiveEnergy(1, Actionable.MODULATE);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
        assertEquals(6, materials.availableAmount(MATERIAL));
        assertEquals(0, consumed.storedEnergy());
        assertEquals(0, products.amount(PRODUCT));
        consumed.receiveEnergy(22, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, process.state().successfulTicks());
        assertEquals(3, materials.amount(MATERIAL));
        assertEquals(11, consumed.storedEnergy());
        assertEquals(1, products.amount(PRODUCT));
        assertEquals(13, generated.storedEnergy());

        var keys = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);
        var codec = RecipeProcessNbt.builder(recipe, "editing-v1").resources(keys).build();
        var restored = codec.decode(machine, codec.encode(process));
        assertEquals(process.identity(), restored.identity());
        assertEquals(process.state(), restored.state());
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, restored.state().lifecycle());
        assertEquals(0, materials.amount(MATERIAL));
        assertEquals(0, consumed.storedEnergy());
        assertEquals(7, products.amount(PRODUCT));
        assertEquals(43, generated.storedEnergy());
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(7, products.amount(PRODUCT));
        assertEquals(43, generated.storedEnergy());
    }

    private static ResourceRequirement complexResources() {
        var matcher = new NamedResourceMatcher(id("input_matcher"),
            (candidate, pattern) -> candidate.equals(pattern) || candidate.equals(ALTERNATE));
        var order = Comparator.comparing((AEKey candidate) -> Objects.requireNonNull(candidate.getId())).reversed();
        var dynamic = List.of(new DynamicInputGroup(matcher, MATERIAL, 29, List.of(MATERIAL, ALTERNATE), 16, order),
            new DynamicInputGroup(matcher, ALTERNATE, 31, List.of(ALTERNATE), 8, order));
        var random = new RandomOutputPool(List.of(new WeightedSampling.Weighted<>(stack(PRODUCT, 17), 2),
            new WeightedSampling.Weighted<>(stack(BYPRODUCT, 19), 5)), 2);
        return new ResourceRequirement(List.of(stack(MATERIAL, 2), stack(ALTERNATE, 3)), List.of(stack(MATERIAL, 5)),
            List.of(stack(PRODUCT, 7)), List.of(stack(PRODUCT, 11), stack(BYPRODUCT, 13)), true,
            new ResourceChance(37, id("yield")), random, List.of(new FuzzyInputGroup(List.of(MATERIAL, ALTERNATE), 23)),
            dynamic, InputAllocation.SINGLE_SOURCE);
    }

    private static void assertPolicies(ResourceRequirement source, ResourceRequirement edited) {
        assertEquals(source.voidExcessOutputs(), edited.voidExcessOutputs());
        assertSame(source.chance(), edited.chance());
        assertSame(source.inputAllocation(), edited.inputAllocation());
    }

    private static void assertInputsEmpty(ResourceRequirement payload) {
        assertTrue(payload.inputs().isEmpty());
        assertTrue(payload.inputsPerTick().isEmpty());
        assertTrue(payload.fuzzyInputs().isEmpty());
        assertTrue(payload.dynamicInputs().isEmpty());
    }

    private static void assertOutputsEmpty(ResourceRequirement payload) {
        assertTrue(payload.outputs().isEmpty());
        assertTrue(payload.outputsPerTick().isEmpty());
        assertNull(payload.randomOutputs());
    }

    private static void assertCanonical(RecipeBuilder builder, ResourceLocation binding) {
        var requirement = builder.requirement(binding);
        assertSame(requirement, requirement.definition());
        assertEquals(binding, requirement.id());
    }

    private static void assertRejected(Class<? extends Throwable> failure, RecipeBuilder builder, Runnable action) {
        var previous = List.copyOf(builder.requirements());
        assertThrows(failure, action::run);
        assertEquals(previous, builder.requirements());
    }

    private static RecipeRequirement<?> requirement(MachineRecipe recipe, ResourceLocation binding) {
        for (var requirement : recipe.orderedRequirements()) if (requirement.id().equals(binding)) return requirement;
        throw new AssertionError("Missing requirement " + binding);
    }

    private static ResourceRequirement resources(MachineRecipe recipe, ResourceLocation binding) {
        return (ResourceRequirement) requirement(recipe, binding).payload();
    }

    private static GenericStack stack(Key key, long amount) {
        return new GenericStack(key, amount);
    }

    private static BufferedResourceHatch hatch(HatchMode mode) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 1000);
        return new BufferedResourceHatch(mode, 2, capacities, () -> {
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("recipe_editing_test", path);
    }
}
