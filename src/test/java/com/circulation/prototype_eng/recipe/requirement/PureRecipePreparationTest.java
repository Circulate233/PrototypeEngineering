package com.circulation.prototype_eng.recipe.requirement;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureRecipePreparationTest {
    private static final KeyType TYPE = new KeyType("pure_recipe_preparation");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("pure_recipe_preparation_test", name);
    }

    private static PlannedDeadline deadline() {
        return PlannedDeadline.after(Duration.ofSeconds(10));
    }

    private static BufferedResourceHatch hatch(HatchMode mode, int slots, AtomicInteger publications) {
        return new BufferedResourceHatch(mode, slots, Map.of(TYPE, 100_000L), publications::incrementAndGet);
    }

    private static MachineWorkSlots host(BufferedResourceHatch... hatches) {
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatches), List.of(), List.of()));
        return new MachineWorkSlots(machine, List.of());
    }

    private static RecipeProcess process(MachineWorkSlots host, int duration, RecipeRequirement<?>... requirements) {
        var process = new RecipeProcess(host.owner(), new MachineRecipe(id("recipe"), duration, List.of(requirements)), 17);
        assertTrue(host.start(process));
        return process;
    }

    private static int finish(PlannedRecipeCoordinator coordinator) {
        int slices = 0;
        while (!coordinator.advance(deadline())) {
            assertTrue(++slices < 10_000, "Legal work must continue to completion without a fixed minimum slice size");
        }
        assertTrue(coordinator.finished());
        return slices;
    }

    private static RecipeRequirement<ResourceRequirement> resource(String name, List<GenericStack> inputs, List<GenericStack> outputs) {
        return RecipeRequirements.RESOURCES.requirement(id(name), new ResourceRequirement(inputs, outputs));
    }

    @Test
    void realOverlayRetriesReuseFrozenPartsAndRejectSameSizeChangedIoFacts() {
        var oldKey = new Key(TYPE, "old_input");
        var currentKey = new Key(TYPE, "current_input");
        var stacks = new ObjectArrayList<GenericStack>();
        for (int index = 0; index < 256; index++) {
            stacks.add(new GenericStack(oldKey, 1));
        }
        var warehouse = hatch(HatchMode.INPUT, 1, new AtomicInteger());
        var storage = Objects.requireNonNull(warehouse.input());
        storage.insert(currentKey, 256, Actionable.MODULATE);
        var requirement = resource("overlay_inputs", stacks, List.of());
        stacks.clear();
        var slots = host(warehouse);
        var process = process(slots, 0, requirement);
        var overlays = RecipeOverlays.getOrCreate(process);
        overlays.put(requirement, "io", RecipeOverlayTypes.RESOURCE_IO, new ResourceRequirementPatch(null, null, null, null, true)).complete();
        assertSame(requirement.payload().inputs(), overlays.resolve(requirement).payload().inputs());
        assertSame(requirement.payload().fuzzyInputs(), overlays.resolve(requirement).payload().fuzzyInputs());
        try (var backend = new Backend()) {
            var first = new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true);
            first.workPerAdvance(1);
            assertTrue(finish(first) > 256);
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            overlays.put(requirement, "io", RecipeOverlayTypes.RESOURCE_IO, new ResourceRequirementPatch(null, null, null, null, true)).complete();
            assertSame(requirement.payload().inputs(), overlays.resolve(requirement).payload().inputs());
            var unchanged = new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true);
            unchanged.workPerAdvance(1);
            finish(unchanged);
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            for (int index = 0; index < 256; index++) {
                stacks.add(new GenericStack(currentKey, 1));
            }
            var patch = new ResourceRequirementPatch(stacks, null, null, null, true);
            stacks.clear();
            overlays.put(requirement, "io", RecipeOverlayTypes.RESOURCE_IO, patch).complete();
            assertSame(patch.inputs(), overlays.resolve(requirement).payload().inputs());
            var changed = new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true);
            changed.workPerAdvance(1);
            assertTrue(finish(changed) > 256);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, storage.amount(currentKey));
        }
    }

    @Test
    void realBuiltinDynamicWorkersOverlapBeforeOriginalOrderedReservations() {
        var entered = new CountDownLatch(2);
        var publications = new AtomicInteger();
        var firstHatch = hatch(HatchMode.INPUT, 1, publications);
        var secondHatch = hatch(HatchMode.INPUT, 1, publications);
        var material = new Key(TYPE, "material");
        assertEquals(1, Objects.requireNonNull(firstHatch.input()).insert(material, 1, Actionable.MODULATE));
        assertEquals(1, Objects.requireNonNull(secondHatch.input()).insert(material, 1, Actionable.MODULATE));
        var matcher = new NamedResourceMatcher(id("overlap"), (_, _) -> {
            entered.countDown();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return true;
        });
        var payload = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            ResourceChance.ALWAYS, null, List.of(), List.of(new DynamicInputGroup(matcher, material, 1)));
        var requirement = RecipeRequirements.RESOURCES.requirement(id("dynamic"), payload);
        var first = host(firstHatch);
        var second = host(secondHatch);
        var one = process(first, 0, requirement);
        var two = process(second, 0, requirement);
        var opaqueCalls = new AtomicInteger();
        var opaqueHost = host();
        var opaque = new RecipeRequirementType<>(id("later_opaque"), new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                opaqueCalls.incrementAndGet();
                assertEquals(0, Objects.requireNonNull(firstHatch.input()).availableAmount(material));
                assertEquals(0, Objects.requireNonNull(secondHatch.input()).availableAmount(material));
                return RequirementTransaction.SUCCESS;
            }
        }).requirement(id("opaque_after_native_reserves"), "owner");
        var three = process(opaqueHost, 0, opaque);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(first, second, opaqueHost), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(1);
            finish(coordinator);
            assertEquals(0, entered.getCount());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, one.state().lifecycle());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, two.state().lifecycle());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, three.state().lifecycle());
            assertEquals(1, opaqueCalls.get());
            assertEquals(0, Objects.requireNonNull(firstHatch.input()).amount(material));
            assertEquals(0, Objects.requireNonNull(secondHatch.input()).amount(material));
        }
    }

    @Test
    void sharedSourceUsesNextSatisfiableCandidateInsteadOfTwoOldBalanceSelections() {
        var publications = new AtomicInteger();
        var shared = hatch(HatchMode.INPUT, 2, publications);
        var a = new Key(TYPE, "a");
        var b = new Key(TYPE, "b");
        var inventory = Objects.requireNonNull(shared.input());
        inventory.insert(b, 1, Actionable.MODULATE);
        inventory.insert(a, 1, Actionable.MODULATE);
        var matcher = new NamedResourceMatcher(id("all"), (_, _) -> true);
        var payload = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            ResourceChance.ALWAYS, null, List.of(), List.of(new DynamicInputGroup(matcher, a, 1, List.of(), 1)));
        var requirement = RecipeRequirements.RESOURCES.requirement(id("dynamic"), payload);
        var first = host(shared);
        var second = host(shared);
        var one = process(first, 0, requirement);
        var two = process(second, 0, requirement);
        try (var backend = new Backend()) {
            finish(new PlannedRecipeCoordinator(backend.tasks, List.of(first, second), deadline(), new PlannedCancellationToken(), () -> true));
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, one.state().lifecycle());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, two.state().lifecycle());
            assertEquals(0, inventory.amount(a));
            assertEquals(0, inventory.amount(b));
        }
    }

    @Test
    void realRandomAndMaxOneDynamicWorkYieldsWithoutPublishingAndMatchesUnslicedRecipe() {
        var keys = new ObjectArrayList<AEKey>();
        var weighted = new ObjectArrayList<WeightedSampling.Weighted<GenericStack>>();
        for (int index = 0; index < 64; index++) {
            var key = new Key(TYPE, "key_" + String.format("%03d", index));
            keys.add(key);
            weighted.add(new WeightedSampling.Weighted<>(new GenericStack(key, index + 1), index + 1));
        }
        var matcher = new NamedResourceMatcher(id("all"), (_, _) -> true);
        var payload = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            new ResourceChance(175, null), new RandomOutputPool(weighted, 3), List.of(),
            List.of(new DynamicInputGroup(matcher, keys.getFirst(), 1, List.of(), 1)));
        var requirement = RecipeRequirements.RESOURCES.requirement(id("random_dynamic"), payload);
        var slicedPublications = new AtomicInteger();
        var slicedInput = hatch(HatchMode.INPUT, 64, slicedPublications);
        var slicedOutput = hatch(HatchMode.OUTPUT, 64, slicedPublications);
        var ordinaryInput = hatch(HatchMode.INPUT, 64, new AtomicInteger());
        var ordinaryOutput = hatch(HatchMode.OUTPUT, 64, new AtomicInteger());
        for (int index = keys.size() - 1; index >= 0; index--) {
            Objects.requireNonNull(slicedInput.input()).insert(keys.get(index), 256, Actionable.MODULATE);
            Objects.requireNonNull(ordinaryInput.input()).insert(keys.get(index), 256, Actionable.MODULATE);
        }
        var sliced = host(slicedInput, slicedOutput);
        var ordinary = host(ordinaryInput, ordinaryOutput);
        var actual = process(sliced, 0, requirement);
        var expected = process(ordinary, 0, requirement);
        actual.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 64);
        expected.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 64);
        slicedPublications.set(0);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(sliced), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(96);
            assertFalse(coordinator.advance(deadline()));
            assertEquals(0, slicedPublications.get(), "Private partial probability/candidate facts cannot publish an output");
            assertTrue(finish(coordinator) > 10);
            assertEquals(ProcessResult.SUCCESS, expected.tick());
            assertEquals(expected.state(), actual.state());
            for (var key : keys) {
                assertEquals(Objects.requireNonNull(ordinaryInput.input()).amount(key), Objects.requireNonNull(slicedInput.input()).amount(key));
                assertEquals(Objects.requireNonNull(ordinaryOutput.output()).amount(key), Objects.requireNonNull(slicedOutput.output()).amount(key));
            }
        }
    }

    @Test
    void modifierAndCheckpointCandidateRollbackFreezeOnlyAtActualChildArrival() {
        var publications = new AtomicInteger();
        var warehouse = hatch(HatchMode.MIXED, 3, publications);
        var material = new Key(TYPE, "material");
        var absent = new Key(TYPE, "absent");
        var product = new Key(TYPE, "product");
        Objects.requireNonNull(warehouse.input()).insert(material, 4, Actionable.MODULATE);
        var modifier = RecipeRequirements.ATTRIBUTES.requirement(id("parallel"), new AttributeRequirement(
            StandardMachineAttributes.PROCESS_PARALLELISM,
            new NumericAttributeModifier("parallel", 1, AttributeModifier.Operation.ADDITION)));
        var blocked = resource("blocked", List.of(new GenericStack(absent, 1)), List.of(new GenericStack(product, 99)));
        var accepted = resource("accepted", List.of(new GenericStack(material, 1)), List.of(new GenericStack(product, 1)));
        var selection = RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(blocked, accepted)));
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(0, selection));
        var host = host(warehouse);
        var process = process(host, 0, modifier, checkpoint);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(32);
            finish(coordinator);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(2, Objects.requireNonNull(warehouse.input()).amount(material));
            assertEquals(2, Objects.requireNonNull(warehouse.output()).amount(product));
            assertEquals(0, Objects.requireNonNull(warehouse.output()).amount(absent));
        }
    }

    @Test
    void oneUnitSlicesContinueActualResourceCaptureChoiceAndReservationWithoutAnAdmissionMinimum() {
        var warehouse = hatch(HatchMode.MIXED, 2, new AtomicInteger());
        var material = new Key(TYPE, "one_unit_material");
        var product = new Key(TYPE, "one_unit_product");
        Objects.requireNonNull(warehouse.input()).insert(material, 1, Actionable.MODULATE);
        var all = new NamedResourceMatcher(id("one_unit"), (_, _) -> true);
        var requirement = RecipeRequirements.RESOURCES.requirement(id("dynamic"), new ResourceRequirement(List.of(), List.of(), List.of(),
            List.of(new GenericStack(product, 1)), false, ResourceChance.ALWAYS, null, List.of(), List.of(new DynamicInputGroup(all, material, 1))));
        var host = host(warehouse);
        var process = process(host, 0, requirement);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(1);
            assertTrue(finish(coordinator) > 10);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, Objects.requireNonNull(warehouse.input()).amount(material));
            assertEquals(1, Objects.requireNonNull(warehouse.output()).amount(product));
        }
    }

    @Test
    void actualEnergyTraversalYieldsAndCancellationRefundsTokensHeldBeforeThePause() {
        var first = new EnergyBuffer(10, () -> {
        });
        var last = new EnergyBuffer(10, () -> {
        });
        first.receiveEnergy(1, Actionable.MODULATE);
        last.receiveEnergy(1, Actionable.MODULATE);
        var sources = new ObjectArrayList<EnergyInput>();
        sources.add(first);
        for (int index = 0; index < 100; index++) {
            sources.add(new EnergyBuffer(10, () -> {
            }));
        }
        sources.add(last);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), sources, List.of()));
        var slots = new MachineWorkSlots(machine, List.of());
        var requirement = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 0, 0, 0, false));
        var process = process(slots, 0, requirement);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(8);
            assertFalse(coordinator.advance(deadline()));
            int slices = 0;
            while (first.energySnapshot().availableEnergy() != 0) {
                assertFalse(coordinator.advance(deadline()));
                assertTrue(++slices < 10_000);
            }
            assertEquals(0, first.energySnapshot().availableEnergy());
            assertEquals(1, first.storedEnergy());
            assertEquals(1, last.energySnapshot().availableEnergy());
            coordinator.cancel(PlannedCancellationToken.Reason.EPOCH_CHANGED);
            finish(coordinator);
            assertEquals(1, first.energySnapshot().availableEnergy());
            assertEquals(1, last.energySnapshot().availableEnergy());
            var retry = new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true);
            retry.workPerAdvance(8);
            assertTrue(finish(retry) > 10);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, first.storedEnergy());
            assertEquals(0, last.storedEnergy());
        }
    }

    @Test
    void builtInQuantityCaptureCopiesIncrementallyAndRestartsAfterARealRevisionChange() {
        var buffer = new SlottedResourceBuffer(1002, Map.of(TYPE, 10L), () -> {
        });
        var first = new Key(TYPE, "a");
        buffer.insert(first, 1, Actionable.MODULATE);
        for (int index = 0; index < 1000; index++) {
            buffer.insert(new Key(TYPE, "z_" + index), 1, Actionable.MODULATE);
        }
        var capture = buffer.beginQuantityCapture();
        assertFalse(capture.advance(new RecipeWorkBudget(3)));
        buffer.insert(first, 2, Actionable.MODULATE);
        int slices = 0;
        while (!capture.advance(new RecipeWorkBudget(8))) {
            assertTrue(++slices < 1000);
        }
        assertTrue(slices > 100);
        assertEquals(3, capture.available().getLong(first));
        assertEquals(1001, capture.available().size());
        assertTrue(capture.current());
        buffer.extract(first, 3, Actionable.MODULATE);
        assertFalse(capture.current());
        assertEquals(3, capture.available().getLong(first));
    }

    @Test
    void lateMatchingKeyAfterSecondCaptureMergeYieldBlocksOldChoiceAndRetriesFromFreshFacts() {
        var buffer = new SlottedResourceBuffer(102, Map.of(TYPE, 10L), () -> {
        });
        var a = new Key(TYPE, "a");
        var b = new Key(TYPE, "b");
        buffer.insert(b, 1, Actionable.MODULATE);
        for (int index = 0; index < 100; index++) {
            buffer.insert(new Key(TYPE, "z_" + index), 1, Actionable.MODULATE);
        }
        var captures = new AtomicInteger();
        var secondProof = new AtomicBoolean();
        var storage = new ResourceStorage() {
            @Override
            public StorageContext context() {
                return buffer.context();
            }

            @Override
            public StorageSnapshot snapshot() {
                return buffer.snapshot();
            }

            @Override
            public StorageSnapshot snapshotFor(AEKey key) {
                return buffer.snapshotFor(key);
            }

            @Override
            public long amount(AEKey key) {
                return buffer.amount(key);
            }

            @Override
            public long availableAmount(AEKey key) {
                return buffer.availableAmount(key);
            }

            @Override
            public long insert(AEKey key, long amount, Actionable mode) {
                return buffer.insert(key, amount, mode);
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode) {
                return buffer.extract(key, amount, mode);
            }

            @Override
            public ReservationResult reserveExtract(
                StorageSnapshot snapshot, AEKey key, long amount) {
                return buffer.reserveExtract(snapshot, key, amount);
            }

            @Override
            public ReservationResult reserveInsert(
                StorageSnapshot snapshot, AEKey key, long amount) {
                return buffer.reserveInsert(snapshot, key, amount);
            }

            @Override
            @SuppressWarnings("ReturnOfInnerClass") // Captured test wrapper records one exact owner proof boundary.
            public QuantityCapture beginQuantityCapture() {
                int number = captures.incrementAndGet();
                var capture = buffer.beginQuantityCapture();
                return new QuantityCapture() {
                    @Override
                    public boolean advance(RecipeWorkBudget budget) {
                        return capture.advance(budget);
                    }

                    @Override
                    public AEKey2LongMap available() {
                        return capture.available();
                    }

                    @Override
                    public boolean current() {
                        boolean valid = capture.current();
                        if (number == 2 && valid) {
                            secondProof.set(true);
                        }
                        return valid;
                    }
                };
            }
        };
        var hatch = new ResourceHatch() {
            @Override
            public ResourceStorage input() {
                return storage;
            }

            @Override
            public ResourceStorage output() {
                return null;
            }

            @Override
            public boolean isValid() {
                return true;
            }
        };
        var all = new NamedResourceMatcher(id("late_key"), (_, _) -> true);
        var requirement = RecipeRequirements.RESOURCES.requirement(id("dynamic"), new ResourceRequirement(List.of(), List.of(), List.of(), List.of(),
            false, ResourceChance.ALWAYS, null, List.of(), List.of(new DynamicInputGroup(all, b, 1, List.of(), 1))));
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        var slots = new MachineWorkSlots(machine, List.of());
        var process = process(slots, 0, requirement);
        try (var backend = new Backend()) {
            var first = new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true);
            first.workPerAdvance(8);
            int slices = 0;
            while (!secondProof.get()) {
                assertFalse(first.advance(deadline()));
                assertTrue(++slices < 10_000);
            }
            assertTrue(first.workYielded());
            buffer.insert(a, 1, Actionable.MODULATE);
            finish(first);
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            assertEquals(1, buffer.amount(a));
            assertEquals(1, buffer.amount(b));
            var retry = new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true);
            retry.workPerAdvance(8);
            finish(retry);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, buffer.amount(a));
            assertEquals(1, buffer.amount(b));
        }
    }

    @Test
    void sourceCaptureDoesNotMixAnOldFirstSourceWithALaterSourceAfterOwnerWorkYields() {
        var first = hatch(HatchMode.INPUT, 2, new AtomicInteger());
        var second = hatch(HatchMode.INPUT, 100, new AtomicInteger());
        var a = new Key(TYPE, "a");
        var b = new Key(TYPE, "b");
        Objects.requireNonNull(first.input()).insert(b, 1, Actionable.MODULATE);
        for (int index = 0; index < 100; index++) {
            Objects.requireNonNull(second.input()).insert(new Key(TYPE, "z_" + index), 1, Actionable.MODULATE);
        }
        var all = new NamedResourceMatcher(id("all_sources"), (_, _) -> true);
        var requirement = RecipeRequirements.RESOURCES.requirement(id("dynamic"), new ResourceRequirement(List.of(), List.of(), List.of(), List.of(),
            false, ResourceChance.ALWAYS, null, List.of(), List.of(new DynamicInputGroup(all, b, 1, List.of(), 1))));
        var host = host(first, second);
        var process = process(host, 0, requirement);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(8);
            assertFalse(coordinator.advance(deadline()));
            Objects.requireNonNull(first.input()).insert(a, 1, Actionable.MODULATE);
            finish(coordinator);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, Objects.requireNonNull(first.input()).amount(a));
            assertEquals(1, Objects.requireNonNull(first.input()).amount(b));
        }
    }

    @Test
    void nestedFlatSelectorWaitsForInnerFallbackBeforeInstallingOuterSelection() {
        var warehouse = hatch(HatchMode.MIXED, 2, new AtomicInteger());
        var missing = new Key(TYPE, "missing");
        var available = new Key(TYPE, "available");
        var product = new Key(TYPE, "product");
        Objects.requireNonNull(warehouse.input()).insert(available, 1, Actionable.MODULATE);
        var one = resource("one", List.of(new GenericStack(missing, 1)), List.of(new GenericStack(product, 99)));
        var two = resource("two", List.of(new GenericStack(available, 1)), List.of(new GenericStack(product, 1)));
        var inner = RecipeRequirements.SELECTIVE.requirement(id("inner"), new SelectiveRequirement(List.of(one, two)));
        var outer = RecipeRequirements.SELECTIVE.requirement(id("outer"), new SelectiveRequirement(List.of(inner)));
        var host = host(warehouse);
        var process = process(host, 0, outer);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(16);
            finish(coordinator);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, Objects.requireNonNull(warehouse.input()).amount(available));
            assertEquals(1, Objects.requireNonNull(warehouse.output()).amount(product));
        }
    }

    @Test
    void opaqueOwnerWrapperKeepsItsSynchronousNestedBlockedFallback() {
        var warehouse = hatch(HatchMode.INPUT, 1, new AtomicInteger());
        var missing = new Key(TYPE, "missing");
        var available = new Key(TYPE, "available");
        Objects.requireNonNull(warehouse.input()).insert(available, 1, Actionable.MODULATE);
        var one = resource("one", List.of(new GenericStack(missing, 1)), List.of());
        var two = resource("two", List.of(new GenericStack(available, 1)), List.of());
        var calls = new AtomicInteger();
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                calls.incrementAndGet();
                var first = one.acquire(process, RecipeStage.START);
                if (first.result() == ProcessResult.SUCCESS) {
                    return first;
                }
                ResourceTransactions.rollback(first.reservations(), first::rollback);
                return two.acquire(process, RecipeStage.START);
            }
        };
        var wrapper = new RecipeRequirementType<>(id("opaque_wrapper"), system).requirement(id("opaque_wrapper"), "owner");
        var host = host(warehouse);
        var process = process(host, 0, wrapper);
        try (var backend = new Backend()) {
            finish(new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true));
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(1, calls.get());
            assertEquals(0, Objects.requireNonNull(warehouse.input()).amount(available));
        }
    }

    @Test
    void completedPureSelectionFactsRemainCleanAfterLaterOwnerFailureAndNewTickMaterialRetry() {
        var warehouse = hatch(HatchMode.INPUT, 1, new AtomicInteger());
        var external = hatch(HatchMode.INPUT, 1, new AtomicInteger());
        var a = new Key(TYPE, "a");
        var b = new Key(TYPE, "b");
        Objects.requireNonNull(warehouse.input()).insert(a, 1, Actionable.MODULATE);
        var matcher = new NamedResourceMatcher(id("a_only"), AEKey::equals);
        var dynamic = RecipeRequirements.RESOURCES.requirement(id("a"), new ResourceRequirement(List.of(), List.of(), List.of(), List.of(),
            false, ResourceChance.ALWAYS, null, List.of(), List.of(new DynamicInputGroup(matcher, a, 1))));
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                var input = Objects.requireNonNull(external.input());
                if (input.availableAmount(b) == 0) {
                    return new ReservedRequirement(RecipeText.ResourcesMissing.blocked("b", "1"), List.of());
                }
                var token = Objects.requireNonNull(input.reserveExtract(input.snapshot(), b, 1).reservation());
                return new ReservedRequirement(ProcessResult.SUCCESS, List.of(token));
            }
        };
        var other = new RecipeRequirementType<>(id("external_b"), system).requirement(id("external_b"), "owner");
        var host = host(warehouse);
        var process = process(host, 0, dynamic, other);
        try (var backend = new Backend()) {
            finish(new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true));
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            assertEquals(1, Objects.requireNonNull(warehouse.input()).amount(a));
            Objects.requireNonNull(external.input()).insert(b, 1, Actionable.MODULATE);
            finish(new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true));
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, Objects.requireNonNull(warehouse.input()).amount(a));
            assertEquals(0, Objects.requireNonNull(external.input()).amount(b));
        }
    }

    @Test
    void transparentRootPauseCannotExposeSameStageOutputsToLaterRootInputs() {
        var key = new Key(TYPE, "product");
        var storage = new SlottedResourceBuffer(1, Map.of(TYPE, 10L), () -> {
        });
        var input = new ResourceHatch() {
            @Override
            public ResourceStorage input() {
                return storage;
            }

            @Override
            public ResourceStorage output() {
                return null;
            }

            @Override
            public boolean isValid() {
                return true;
            }
        };
        var output = new ResourceHatch() {
            @Override
            public ResourceStorage input() {
                return null;
            }

            @Override
            public ResourceStorage output() {
                return storage;
            }

            @Override
            public boolean isValid() {
                return true;
            }
        };
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(input, output), List.of(), List.of()));
        var slots = new MachineWorkSlots(machine, List.of());
        var produce = RecipeRequirements.RESOURCES.requirement(id("produce_tick"), new ResourceRequirement(List.of(), List.of(),
            List.of(new GenericStack(key, 1)), List.of(), false));
        var consume = RecipeRequirements.RESOURCES.requirement(id("consume_tick"), new ResourceRequirement(List.of(),
            List.of(new GenericStack(key, 1)), List.of(), List.of(), false));
        var process = process(slots, 1, produce, consume);
        try (var backend = new Backend()) {
            finish(new PlannedRecipeCoordinator(backend.tasks, List.of(slots), deadline(), new PlannedCancellationToken(), () -> true));
            assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
            assertEquals(0, process.state().successfulTicks());
            assertEquals(0, storage.amount(key));
        }
    }

    @Test
    void stoppedLateFailingSliceCannotMutatePreviouslyAcceptedCachedProgress() {
        var token = new PlannedCancellationToken();
        var created = new AtomicInteger();
        var publications = new AtomicInteger();
        class Cursor implements RecipePureComputation {
            private final int generation = created.incrementAndGet();
            private int advances;

            @Override
            public boolean advance(RecipeWorkBudget budget) {
                advances++;
                if (generation != 1) {
                    return true;
                }
                if (advances == 1) {
                    budget.take(budget.remaining());
                    return false;
                }
                token.cancel(PlannedCancellationToken.Reason.EPOCH_CHANGED);
                throw new IllegalStateException("Late kernel failure after stopped result publication");
            }
        }
        var system = new RecipeRequirementSystem<String>() {
            @Override
            @SuppressWarnings("ReturnOfInnerClass") // Exercises a captured owner binding.
            public RequirementPreparation prepare(
                RecipeProcess process, RecipeRequirement<String> requirement, RecipeStage stage) {
                if (stage != RecipeStage.START) {
                    return null;
                }
                var previous = process.pureFacts(stage);
                RecipePureComputation cursor = previous instanceof Cursor retained ? retained : new Cursor();
                return new RequirementPreparation() {
                    @Override
                    public RecipePureComputation computation() {
                        return cursor;
                    }

                    @Override
                    public RequirementTransaction acquire() {
                        return RequirementTransaction.SUCCESS;
                    }

                    @Override
                    public void retainPureFacts() {
                        publications.incrementAndGet();
                        process.retainPureFacts(stage, cursor);
                    }
                };
            }
        };
        var requirement = new RecipeRequirementType<>(id("mutable_cache"), system).requirement(id("cached"), "owner");
        var host = host();
        var process = process(host, 0, requirement);
        try (var backend = new Backend()) {
            var first = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), token, () -> true);
            first.workPerAdvance(16);
            assertFalse(first.advance(deadline()));
            assertEquals(1, publications.get());
            finish(first);
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            finish(new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true));
            assertEquals(2, created.get());
            assertEquals(2, publications.get());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        }
    }

    @Test
    void throwingOwnerFactPublicationIsNotReplayedAndDoesNotTrapTheOldPrepareBarrier() {
        var publications = new AtomicInteger();
        var failure = new IllegalStateException("owner publication");
        var system = new RecipeRequirementSystem<String>() {
            @Override
            @SuppressWarnings("ReturnOfInnerClass") // Exercises a captured owner binding.
            public RequirementPreparation prepare(
                RecipeProcess process, RecipeRequirement<String> requirement, RecipeStage stage) {
                if (stage != RecipeStage.START) {
                    return null;
                }
                return new RequirementPreparation() {
                    @Override
                    public RecipePureComputation computation() {
                        return RecipeWorkBudget::take;
                    }

                    @Override
                    public RequirementTransaction acquire() {
                        return RequirementTransaction.SUCCESS;
                    }

                    @Override
                    public void retainPureFacts() {
                        publications.incrementAndGet();
                        throw failure;
                    }
                };
            }
        };
        var requirement = new RecipeRequirementType<>(id("throwing_publication"), system).requirement(id("publication"), "owner");
        var host = host();
        process(host, 0, requirement);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true);
            assertEquals(failure, assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())));
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, publications.get());
            assertTrue(coordinator.finished());
        }
    }

    @Test
    void researchMaterialCycleCompletesOneTickAndEndGrantAfterPureSlices() {
        var publications = new AtomicInteger();
        var warehouse = hatch(HatchMode.MIXED, 2, publications);
        var material = new Key(TYPE, "research_material");
        var product = new Key(TYPE, "research_product");
        Objects.requireNonNull(warehouse.input()).insert(material, 3, Actionable.MODULATE);
        var requirement = RecipeRequirements.RESOURCES.requirement(id("materials"), new ResourceRequirement(
            List.of(new GenericStack(material, 1)), List.of(new GenericStack(material, 1)), List.of(), List.of(new GenericStack(product, 1)), false));
        var machine = new MachineInstance();
        var cycle = RecipeProcess.materialCycle(machine, id("research_cycle"), List.of(requirement), new MachineHatches(List.of(warehouse), List.of(), List.of()), 17);
        var grants = new AtomicInteger();
        try (var backend = new Backend()) {
            var coordinator = PlannedRecipeCoordinator.forProcess(backend.tasks, cycle, deadline(), new PlannedCancellationToken(), () -> true,
                stage -> stage.phase() == RecipeStage.END ? List.of(new ReversibleTransaction() {
                    @Override
                    public void commit() {
                        grants.incrementAndGet();
                    }

                    @Override
                    public void rollback() {
                        grants.decrementAndGet();
                    }
                }) : List.of());
            coordinator.workPerAdvance(8);
            assertTrue(finish(coordinator) > 1);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, cycle.state().lifecycle());
            assertEquals(1, cycle.state().successfulTicks());
            assertEquals(1, grants.get());
            assertEquals(1, Objects.requireNonNull(warehouse.input()).amount(material));
            assertEquals(1, Objects.requireNonNull(warehouse.output()).amount(product));
            cycle.requirePersistenceBoundary();
        }
    }

    private static class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final ExecutorService firstSource = Executors.newSingleThreadExecutor();
        private final ExecutorService secondSource = Executors.newSingleThreadExecutor();
        private final ExecutorService[] sources = {firstSource, secondSource};
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, sources);

        @Override
        public void close() {
            workers.close();
            for (var source : sources) {
                source.close();
            }
        }
    }
}
