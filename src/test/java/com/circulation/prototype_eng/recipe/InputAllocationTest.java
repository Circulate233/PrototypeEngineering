package com.circulation.prototype_eng.recipe;

import it.unimi.dsi.fastutil.objects.Reference2LongMaps;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanProbes;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputAllocationTest {
    private static final int FIXTURE_SLICE_LIMIT = 10000;
    private static final KeyType TYPE = new KeyType("input_allocation");
    private static final Key MATERIAL = new Key(TYPE, "material");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("input_allocation_test", name);
    }

    private static BufferedResourceHatch input(long amount) {
        var hatch = new BufferedResourceHatch(HatchMode.INPUT, 2, Reference2LongMaps.singleton(TYPE, 100L), () -> {
        });
        Objects.requireNonNull(hatch.input()).insert(MATERIAL, amount, Actionable.MODULATE);
        return hatch;
    }

    private static long amount(ResourceHatch hatch) {
        return Objects.requireNonNull(hatch.input()).amount(MATERIAL);
    }

    private static EnergyBuffer energy(long amount, long limit) {
        var buffer = new EnergyBuffer(100, 100, limit, () -> {
        });
        buffer.receiveEnergy(amount, Actionable.MODULATE);
        return buffer;
    }

    private static RecipeProcess process(MachineHatches hatches, int duration, RecipeRequirement<?>... requirements) {
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, hatches);
        return new RecipeProcess(owner, new MachineRecipe(id("recipe"), duration, List.of(requirements)), 17);
    }

    private static RecipeRequirement<ResourceRequirement> resource(ResourceRequirement payload) {
        return RecipeRequirements.RESOURCES.requirement(id("material"), payload);
    }

    private static PlannedDeadline deadline() {
        return PlannedDeadline.after(Duration.ofSeconds(10));
    }

    private static void finish(PlannedRecipeCoordinator coordinator) {
        int slices = 0;
        while (!coordinator.advance(deadline())) {
            if (coordinator.hasActiveSources()) coordinator.awaitExit();
            assertTrue(++slices < FIXTURE_SLICE_LIMIT, "One-unit work must make progress");
        }
        assertTrue(coordinator.finished());
    }

    private static ProcessResult run(RecipeProcess process, boolean planned) {
        if (!planned) return process.tick();
        var host = new MachineWorkSlots(process.owner(), List.of());
        assertTrue(host.start(process));
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(1);
            try {
                finish(coordinator);
            } catch (RuntimeException | Error failure) {
                for (int attempt = 0; attempt < FIXTURE_SLICE_LIMIT && !coordinator.finished(); attempt++) {
                    if (coordinator.hasActiveSources()) coordinator.awaitExit();
                    try {
                        coordinator.advance(deadline());
                    } catch (RuntimeException | Error cleanup) {
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                }
                assertTrue(coordinator.finished(), "Exceptional allocation must retain and finish original cleanup");
                throw failure;
            }
        }
        return process.state().result();
    }

    private static String identity(StorageContext context) {
        context.lock();
        try {
            return context.sourceIdentity();
        } finally {
            context.unlock();
        }
    }

    @Test
    void singleSourceRejectsCombinedBalancesWhileDefaultStillSplits() {
        for (boolean planned : List.of(false, true)) {
            for (var policy : List.of(InputAllocation.SPLITTABLE, InputAllocation.SINGLE_SOURCE)) {
                var first = input(4);
                var second = input(6);
                var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 10)), List.of()).withInputAllocation(policy);
                var process = process(new MachineHatches(List.of(first, second), List.of(), List.of()), 0, resource(payload));
                var result = run(process, planned);
                if (policy == InputAllocation.SPLITTABLE) {
                    assertSame(ProcessResult.SUCCESS, result);
                    assertEquals(0, amount(first) + amount(second));
                } else {
                    assertInstanceOf(ProcessResult.Blocked.class, result);
                    assertEquals(4, amount(first));
                    assertEquals(6, amount(second));
                    assertEquals(4, Objects.requireNonNull(first.input()).availableAmount(MATERIAL));
                }
            }
        }
    }

    @Test
    void singleSourceChoosesFirstSufficientDistinctBalance() {
        for (boolean planned : List.of(false, true)) {
            var first = input(4);
            var second = input(6);
            var third = input(10);
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 10)), List.of())
                .withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var process = process(new MachineHatches(List.of(first, first, second, third), List.of(), List.of()), 0, resource(payload));
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(4, amount(first));
            assertEquals(6, amount(second));
            assertEquals(0, amount(third));
        }
    }

    @Test
    void equalKeysKeepIndependentInputProjections() {
        for (boolean planned : List.of(false, true)) {
            var first = input(6);
            var second = input(6);
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 6), new GenericStack(MATERIAL, 6)), List.of())
                .withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var process = process(new MachineHatches(List.of(first, second), List.of(), List.of()), 0, resource(payload));
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(0, amount(first));
            assertEquals(0, amount(second));
        }
    }

    @Test
    void independentLongProjectionsDoNotOverflowAnUnneededJointKeyTotal() {
        for (boolean planned : List.of(false, true)) {
            var first = new BufferedResourceHatch(HatchMode.INPUT, 1, Reference2LongMaps.singleton(TYPE, Long.MAX_VALUE), () -> {
            });
            var second = new BufferedResourceHatch(HatchMode.INPUT, 1, Reference2LongMaps.singleton(TYPE, Long.MAX_VALUE), () -> {
            });
            Objects.requireNonNull(first.input()).insert(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE);
            Objects.requireNonNull(second.input()).insert(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE);
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, Long.MAX_VALUE),
                new GenericStack(MATERIAL, Long.MAX_VALUE)), List.of()).withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var process = process(new MachineHatches(List.of(first, second), List.of(), List.of()), 0, resource(payload));
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(0, amount(first));
            assertEquals(0, amount(second));
        }
    }

    @Test
    void overflowingCustomAvailabilityIsRejectedBeforeTheCallbackOrPayment() {
        for (boolean planned : List.of(false, true)) {
            var first = new BufferedResourceHatch(HatchMode.INPUT, 1, Reference2LongMaps.singleton(TYPE, Long.MAX_VALUE), () -> {
            });
            var second = new BufferedResourceHatch(HatchMode.INPUT, 1, Reference2LongMaps.singleton(TYPE, Long.MAX_VALUE), () -> {
            });
            Objects.requireNonNull(first.input()).insert(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE);
            Objects.requireNonNull(second.input()).insert(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE);
            var policy = InputAllocation.custom(id("overflow"), _ -> {
                throw new AssertionError("Overflow must precede callback");
            });
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1)), List.of()).withInputAllocation(policy);
            var process = process(new MachineHatches(List.of(first, second), List.of(), List.of()), 0, resource(payload));
            assertThrows(ArithmeticException.class, () -> run(process, planned));
            assertEquals(Long.MAX_VALUE, amount(first));
            assertEquals(Long.MAX_VALUE, amount(second));
        }
    }

    @Test
    void customAllocationReceivesFrozenFactsAndControlsActualSourceAmounts() {
        for (boolean planned : List.of(false, true)) {
            var first = input(9);
            var second = input(9);
            int[] calls = {0};
            var policy = InputAllocation.custom(id("split_seven_three"), context -> {
                calls[0]++;
                assertSame(MATERIAL, context.resource());
                assertSame(RecipeStage.START, context.stage());
                assertEquals(0, context.projection());
                assertEquals(10, context.amount());
                assertEquals(2, context.sources().size(), "Alias inputs cannot become separate allocation sources");
                assertThrows(UnsupportedOperationException.class, () -> context.sources().clear());
                return List.of(new InputAllocation.Portion(1, 7), new InputAllocation.Portion(0, 3));
            });
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 10)), List.of()).withInputAllocation(policy);
            var process = process(new MachineHatches(List.of(first, first, second), List.of(), List.of()), 0, resource(payload));
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(6, amount(first));
            assertEquals(2, amount(second));
            assertEquals(1, calls[0]);
        }
    }

    @Test
    void customAllocationCanSelectByOtherResourcesInTheInitialSnapshot() {
        for (boolean planned : List.of(false, true)) {
            var marker = new Key(TYPE, "marker");
            var first = input(8);
            var second = input(8);
            Objects.requireNonNull(second.input()).insert(marker, 3, Actionable.MODULATE);
            var policy = InputAllocation.custom(id("marked_source"), context -> {
                var selected = context.sources().stream().filter(source -> source.resources().getLong(marker) == 3).findFirst().orElseThrow();
                assertThrows(UnsupportedOperationException.class, () -> selected.resources().clear());
                assertThrows(UnsupportedOperationException.class,
                    () -> selected.resources().object2LongEntrySet().iterator().next().setValue(99L));
                Map<AEKey, Long> boxed = selected.resources();
                assertThrows(UnsupportedOperationException.class,
                    () -> boxed.entrySet().iterator().next().setValue(99L));
                assertEquals(8, selected.resources().getLong(MATERIAL), "Initial snapshot survives earlier projections");
                assertEquals(context.projection() == 0 ? 8 : 4, selected.available());
                return List.of(new InputAllocation.Portion(selected.index(), context.amount()));
            });
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 4), new GenericStack(MATERIAL, 4)), List.of())
                .withInputAllocation(policy);
            var process = process(new MachineHatches(List.of(first, second), List.of(), List.of()), 0, resource(payload));
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(8, amount(first));
            assertEquals(0, amount(second));
            assertEquals(3, Objects.requireNonNull(second.input()).amount(marker));
        }
    }

    @Test
    void sourceOwnsEvenAnExternallyWrappedQuantityMap() {
        var original = new AEKey2LongMap.LinkdOpenHashMap();
        original.addTo(MATERIAL, 8);
        var source = new InputAllocation.Source(0, "external:source", 0, 8,
            new AEKey2LongMap.UnmodifiableMap(original), 0, 8);
        original.object2LongEntrySet().getFirst().setValue(99L);
        assertEquals(8, source.resources().getLong(MATERIAL), "An external wrapper cannot lend mutable ownership");
        assertThrows(UnsupportedOperationException.class,
            () -> source.resources().object2LongEntrySet().iterator().next().setValue(7L));
        Map<AEKey, Long> boxed = source.resources();
        assertThrows(UnsupportedOperationException.class, () -> boxed.entrySet().iterator().next().setValue(7L));
    }

    @Test
    void bothNativeSnapshotExportsRejectEntryWritesAndOwnTheirValues() {
        var storage = new SlottedResourceBuffer(2, Reference2LongMaps.singleton(TYPE, 10L), () -> {
        });
        storage.insert(MATERIAL, 8, Actionable.MODULATE);
        var snapshot = storage.snapshot();
        var capture = storage.beginQuantityCapture();
        boolean done = false;
        for (int step = 0; !done && step < 20; step++) done = capture.advance(new RecipeWorkBudget(1));
        assertTrue(done);
        for (var quantities : List.of(snapshot.available(), capture.available())) {
            assertThrows(UnsupportedOperationException.class,
                () -> quantities.object2LongEntrySet().iterator().next().setValue(99L));
            @SuppressWarnings("UnnecessaryLocalVariable") // The Map static type deliberately selects the boxed entry API.
            Map<AEKey, Long> boxed = quantities;
            assertThrows(UnsupportedOperationException.class, () -> boxed.entrySet().iterator().next().setValue(99L));
        }
        assertEquals(8, storage.amount(MATERIAL));
        storage.insert(MATERIAL, 1, Actionable.MODULATE);
        assertEquals(8, snapshot.available().getLong(MATERIAL));
        assertEquals(8, capture.available().getLong(MATERIAL));
        assertEquals(9, storage.amount(MATERIAL));
    }

    @Test
    void sparseNativeInputKeepsEveryProbeAndPlannedSliceWithinOneSlotVisit() {
        var custom = InputAllocation.custom(id("sparse"), context ->
            List.of(new InputAllocation.Portion(context.sources().getFirst().index(), context.amount())));
        for (var policy : List.of(InputAllocation.SINGLE_SOURCE, custom)) {
            var key = new SlotVisitKey();
            var storage = new SlottedResourceBuffer(4096, Reference2LongMaps.singleton(TYPE, 10L), () -> {
            });
            storage.insert(4095, key, 3, Actionable.MODULATE);
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
                    return storage.isValid();
                }
            };
            var payload = new ResourceRequirement(List.of(new GenericStack(key, 1)), List.of()).withInputAllocation(policy);
            var process = process(new MachineHatches(List.of(hatch), List.of(), List.of()), 0, resource(payload));
            var probe = RecipeScanProbes.begin(process, List.of(), List.of());
            // Each native pause also remains in the original acquisition/source inventory and
            // rollback history. Diagnostic counts establish five linear passes, not one. Keep
            // the strict one-slot-per-unit assertion while allowing those separately charged passes.
            int totalSliceLimit = Math.addExact(Math.multiplyExact(storage.size(), 6), 1024);
            int probeSteps = 0;
            for (; !probe.finished() && probeSteps < totalSliceLimit; probeSteps++) {
                int before = key.emptySlotVisits;
                var budget = new RecipeWorkBudget(1);
                for (var source : probe.sources()) {
                    if (probe.resolvingSources())
                        probe.finishSourceResolution(source, source.resolveWithinBudget(budget));
                    else if (source.state() == ResourceBatch.SourceState.RESERVED) source.applyWithinBudget(budget);
                }
                var computation = probe.computation();
                if (computation != null && computation.advance(budget)) probe.finishComputation(computation);
                probe.advance(budget, () -> true);
                assertTrue(key.emptySlotVisits - before <= 1, "A scan slice must not hide a full sparse warehouse traversal");
            }
            assertTrue(probe.finished(), () -> "Sparse probe stalled: policy=" + policy.kind() + ", emptyVisits=" + key.emptySlotVisits
                + ", computation=" + (probe.computation() != null) + ", resolving=" + probe.resolvingSources()
                + ", sourceStates=" + probe.sources().stream().map(ResourceBatch.Source::state).toList());
            assertSame(ProcessResult.SUCCESS, probe.result());
            assertEquals(3, storage.availableAmount(key), "The real scan must release its original reservation");
            var host = new MachineWorkSlots(process.owner(), List.of());
            assertTrue(host.start(process));
            try (var backend = new Backend()) {
                var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(),
                    new PlannedCancellationToken(), () -> true);
                coordinator.workPerAdvance(1);
                // Real execution also copies the stage inventory and performs prepare/commit/
                // validate/complete with separately budgeted participant selection and callbacks.
                int executionSliceLimit = Math.addExact(Math.multiplyExact(storage.size(), 14), 2048);
                int executionSteps = 0;
                for (; !coordinator.finished() && executionSteps < executionSliceLimit; executionSteps++) {
                    int before = key.emptySlotVisits;
                    coordinator.advance(deadline());
                    if (coordinator.hasActiveSources()) coordinator.awaitExit();
                    assertTrue(key.emptySlotVisits - before <= 1, "A planned slice must not hide a full sparse warehouse traversal");
                }
                assertTrue(coordinator.finished());
            }
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(2, storage.amount(key));
        }
    }

    @Test
    void customSourceIdentitySurvivesReconstructionAndSourceReordering() {
        var policy = InputAllocation.custom(id("stable_identity"), context -> {
            var selected = context.sources().stream().filter(source -> source.identity().equals("addon:preferred")).findFirst().orElseThrow();
            return List.of(new InputAllocation.Portion(selected.index(), context.amount()));
        });
        for (boolean planned : List.of(false, true)) {
            for (boolean reversed : List.of(false, true)) {
                var preferred = input(8);
                var other = input(8);
                Objects.requireNonNull(preferred.input()).context().bindSourceIdentity("addon:preferred");
                Objects.requireNonNull(other.input()).context().bindSourceIdentity("addon:other");
                var sources = reversed ? List.of(other, preferred, other) : List.of(preferred, preferred, other);
                var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 5)), List.of()).withInputAllocation(policy);
                assertSame(ProcessResult.SUCCESS, run(process(new MachineHatches(sources, List.of(), List.of()), 0, resource(payload)), planned));
                assertEquals(3, amount(preferred));
                assertEquals(8, amount(other));
            }
        }
    }

    @Test
    void bindingInvalidatesEarlierFactsAndCannotRelabelActiveOrBoundSources() {
        var hatch = input(8);
        var storage = Objects.requireNonNull(hatch.input());
        var before = storage.snapshot();
        storage.context().bindSourceIdentity("addon:bound");
        assertFalse(storage.context().current(before.revision()));
        assertNotEquals(ReservationResult.Status.ACCEPTED, storage.reserveExtract(before, MATERIAL, 1).status());
        assertThrows(IllegalStateException.class, () -> storage.context().bindSourceIdentity("addon:other"));
        var unbound = Objects.requireNonNull(input(8).input());
        var reservation = Objects.requireNonNull(unbound.reserveExtract(unbound.snapshot(), MATERIAL, 1).reservation());
        assertThrows(IllegalStateException.class, () -> unbound.context().bindSourceIdentity("addon:too_late"));
        assertTrue(ResourceTransactions.commit(List.of(reservation)));
        assertEquals(7, unbound.amount(MATERIAL));
    }

    @Test
    void nativeEnergyRebuildKeepsPhysicalLabelsAndMovementRetiresOldBalances() {
        var tile = new LocatedEnergy();
        var input = Objects.requireNonNull(tile.inputStorage());
        var output = Objects.requireNonNull(tile.outputStorage());
        input.restore(12);
        output.restore(4);
        var identity = identity(input.context());
        assertNotEquals(identity, identity(output.context()));
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        tile.loadTag(saved);
        assertFalse(input.isValid());
        assertFalse(output.isValid());
        var restored = Objects.requireNonNull(tile.inputStorage());
        assertEquals(identity, identity(restored.context()));
        assertEquals(12, restored.storedEnergy());
        tile.setPos(new BlockPos(3, 4, 5));
        assertFalse(restored.isValid());
        assertEquals(0, restored.extractEnergy(1, Actionable.MODULATE));
        tile.validate();
        var moved = Objects.requireNonNull(tile.inputStorage());
        assertNotEquals(identity, identity(moved.context()));
        assertEquals(12, moved.storedEnergy());
        var movedIdentity = identity(moved.context());
        tile.onChunkUnload();
        tile.validate();
        assertFalse(moved.isValid());
        assertEquals(movedIdentity, identity(Objects.requireNonNull(tile.inputStorage()).context()));
        assertEquals(4, Objects.requireNonNull(tile.outputStorage()).storedEnergy());
    }

    @Test
    void duplicateLabelsOnDifferentBalancesCannotAmbiguouslySelectACustomSource() {
        for (boolean planned : List.of(false, true)) {
            var first = input(8);
            var second = input(8);
            Objects.requireNonNull(first.input()).context().bindSourceIdentity("addon:duplicate");
            Objects.requireNonNull(second.input()).context().bindSourceIdentity("addon:duplicate");
            var policy = InputAllocation.custom(id("duplicate_identity"), _ -> {
                throw new AssertionError("Ambiguous identity must fail first");
            });
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1)), List.of()).withInputAllocation(policy);
            var process = process(new MachineHatches(List.of(first, second), List.of(), List.of()), 0, resource(payload));
            assertThrows(IllegalArgumentException.class, () -> run(process, planned));
            assertEquals(8, amount(first));
            assertEquals(8, amount(second));
        }
    }

    @Test
    void resourceStorageReconstructionRetiresOldFactsAndRebindsPhysicalLabels() {
        // The real resource tile's config requires initialized AEConfig. Exercise its existing
        // configurable storage save/load/retire boundary without bootstrapping a game or global config.
        var original = new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(TYPE, 10L), () -> {
        });
        var input = Objects.requireNonNull(original.input());
        var output = Objects.requireNonNull(original.output());
        input.context().bindSourceIdentity("identity_test:resource/0/input");
        output.context().bindSourceIdentity("identity_test:resource/0/output");
        var snapshot = input.snapshot();
        var identity = identity(input.context());
        assertNotEquals(identity, identity(output.context()));
        var saved = new NBTTagCompound();
        original.save(saved);
        original.invalidate();
        var reattached = new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(TYPE, 10L), () -> {
        });
        reattached.load(saved);
        var restored = Objects.requireNonNull(reattached.input());
        restored.context().bindSourceIdentity("identity_test:resource/0/input");
        assertNotSame(input, restored);
        assertFalse(input.context().current(snapshot.revision()));
        assertEquals(identity, identity(restored.context()));
        reattached.invalidate();
        assertFalse(restored.isValid());
        var movedHatch = new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(TYPE, 10L), () -> {
        });
        movedHatch.load(saved);
        var moved = Objects.requireNonNull(movedHatch.input());
        moved.context().bindSourceIdentity("identity_test:resource/6/7/8/input");
        assertTrue(moved.isValid());
        assertNotEquals(identity, identity(moved.context()));
    }

    @Test
    void malformedCustomPlansFailBeforePaymentAndReleaseEarlierRequirements() {
        var strategies = List.<InputAllocation.Strategy>of(
            _ -> List.of(),
            _ -> List.of(new InputAllocation.Portion(0, 3)),
            _ -> List.of(new InputAllocation.Portion(0, 5)),
            _ -> List.of(new InputAllocation.Portion(2, 4)),
            _ -> List.of(new InputAllocation.Portion(0, 2), new InputAllocation.Portion(0, 2)));
        for (boolean planned : List.of(false, true)) {
            for (var strategy : strategies) {
                var first = input(8);
                var second = input(8);
                var earlier = RecipeRequirements.RESOURCES.requirement(id("earlier"),
                    new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1)), List.of()));
                var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 4)), List.of())
                    .withInputAllocation(InputAllocation.custom(id("invalid"), strategy));
                var process = process(new MachineHatches(List.of(first, second), List.of(), List.of()), 0, earlier, resource(payload));
                assertThrows(IllegalArgumentException.class, () -> run(process, planned));
                assertEquals(8, amount(first));
                assertEquals(8, amount(second));
                assertEquals(8, Objects.requireNonNull(first.input()).availableAmount(MATERIAL));
            }
        }
    }

    @Test
    void insufficientCustomInputDoesNotInvokeTheStrategy() {
        for (boolean planned : List.of(false, true)) {
            var first = input(3);
            var policy = InputAllocation.custom(id("must_not_run"), _ -> {
                throw new AssertionError("Insufficient facts must block first");
            });
            var process = process(new MachineHatches(List.of(first), List.of(), List.of()), 0,
                resource(new ResourceRequirement(List.of(new GenericStack(MATERIAL, 4)), List.of()).withInputAllocation(policy)));
            assertInstanceOf(ProcessResult.Blocked.class, run(process, planned));
            assertEquals(3, amount(first));
        }
    }

    @Test
    void independentEnergyHonorsSingleSourceAndPerOperationLimits() {
        for (boolean planned : List.of(false, true)) {
            var first = energy(20, 6);
            var second = energy(10, 10);
            var payload = new EnergyRequirement(10, 0, 0, 0, false).withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var process = process(new MachineHatches(List.of(), List.of(first, first, second), List.of()), 0,
                RecipeRequirements.ENERGY.requirement(id("energy"), payload));
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(20, first.storedEnergy());
            assertEquals(0, second.storedEnergy());
            var blocked = process(new MachineHatches(List.of(), List.of(first, first), List.of()), 0,
                RecipeRequirements.ENERGY.requirement(id("energy"), payload));
            assertInstanceOf(ProcessResult.Blocked.class, run(blocked, planned));
            assertEquals(20, first.energySnapshot().availableEnergy());
        }
    }

    @Test
    void customEnergyRunsForBothInputStagesWithoutInventingAResourceKey() {
        for (boolean planned : List.of(false, true)) {
            var first = energy(10, 10);
            var second = energy(10, 10);
            var output = energy(0, 100);
            int[] calls = {0};
            var policy = InputAllocation.custom(id("second_energy"), context -> {
                assertNull(context.resource());
                assertEquals(0, context.projection());
                assertEquals(calls[0]++ == 0 ? RecipeStage.START : RecipeStage.TICK, context.stage());
                return List.of(new InputAllocation.Portion(1, context.amount()));
            });
            var payload = new EnergyRequirement(2, 3, 1, 4, false).withInputAllocation(policy);
            var process = process(new MachineHatches(List.of(), List.of(first, second), List.of(output)), 1,
                RecipeRequirements.ENERGY.requirement(id("energy"), payload));
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(10, first.storedEnergy());
            assertEquals(5, second.storedEnergy());
            assertEquals(5, output.storedEnergy());
            assertEquals(2, calls[0]);
        }
    }

    @Test
    void overlaysAndFinalParallelismKeepInputAllocation() {
        for (boolean planned : List.of(false, true)) {
            var first = input(6);
            var second = input(12);
            var base = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1)), List.of())
                .withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var payload = new ResourceRequirementPatch(List.of(new GenericStack(MATERIAL, 3)), null, null, null, null).apply(base);
            var firstEnergy = energy(6, 100);
            var secondEnergy = energy(12, 100);
            var energyBase = new EnergyRequirement(0, 1, 0, 0, false).withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var energyPayload = new EnergyRequirementPatch(3L, null, null).apply(energyBase);
            var process = process(new MachineHatches(List.of(first, second), List.of(firstEnergy, secondEnergy), List.of()), 1,
                resource(payload), RecipeRequirements.ENERGY.requirement(id("energy"), energyPayload));
            process.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, 3);
            assertSame(ProcessResult.SUCCESS, run(process, planned));
            assertEquals(6, amount(first));
            assertEquals(3, amount(second));
            assertEquals(6, firstEnergy.storedEnergy());
            assertEquals(3, secondEnergy.storedEnergy());
        }
    }

    @Test
    void probabilityPreflightAlsoRequiresOneWholeSource() {
        for (boolean planned : List.of(false, true)) {
            var first = input(1);
            var second = input(1);
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 2)), List.of(), List.of(), List.of(),
                false, new ResourceChance(0, null), null).withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var hatches = new MachineHatches(List.of(first, second), List.of(), List.of());
            assertInstanceOf(ProcessResult.Blocked.class, run(process(hatches, 0, resource(payload)), planned));
            Objects.requireNonNull(second.input()).insert(MATERIAL, 1, Actionable.MODULATE);
            assertSame(ProcessResult.SUCCESS, run(process(hatches, 0, resource(payload)), planned));
            assertEquals(1, amount(first));
            assertEquals(2, amount(second));
        }
    }

    @Test
    void staleSourceRejectionDoesNotTurnAProposalIntoPayment() {
        for (boolean planned : List.of(false, true)) {
            var backing = input(10);
            var source = new ChangingSource(Objects.requireNonNull(backing.input()));
            var hatch = new ResourceHatch() {
                @Override
                public ResourceStorage input() {
                    return source;
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
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 10)), List.of())
                .withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var process = process(new MachineHatches(List.of(hatch), List.of(), List.of()), 0, resource(payload));
            assertInstanceOf(ProcessResult.Blocked.class, run(process, planned));
            assertEquals(11, amount(backing), "Only the independently observed change may remain");
            assertEquals(11, source.availableAmount(MATERIAL));
        }
    }

    @Test
    void plannedHostsShareTheActualRemainingBalancesAndCompensateLaterBusinessFailure() {
        for (boolean fail : List.of(false, true)) {
            var first = input(6);
            var second = input(6);
            var hatches = new MachineHatches(List.of(first, second), List.of(), List.of());
            var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 6)), List.of())
                .withInputAllocation(InputAllocation.SINGLE_SOURCE);
            var failure = new IllegalArgumentException("later business failure");
            var fault = new RecipeRequirementType<>(id("fault"), new RecipeRequirementSystem<String>() {
                @Override
                @SuppressWarnings("ReturnOfInnerClass") // This transaction retains this test's exact failure instance.
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                    return new RequirementTransaction() {
                        @Override
                        public ProcessResult result() {
                            return ProcessResult.SUCCESS;
                        }

                        @Override
                        public void commit() {
                            if (fail) throw failure;
                        }

                        @Override
                        public void rollback() {
                        }
                    };
                }
            }).requirement(id("fault"), "later");
            var one = process(hatches, 0, resource(payload));
            var two = process(hatches, 0, resource(payload), fault);
            var oneHost = new MachineWorkSlots(one.owner(), List.of());
            var twoHost = new MachineWorkSlots(two.owner(), List.of());
            assertTrue(oneHost.start(one));
            assertTrue(twoHost.start(two));
            try (var backend = new Backend()) {
                var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(oneHost, twoHost), deadline(),
                    new PlannedCancellationToken(), () -> true);
                coordinator.workPerAdvance(fail ? 10000 : 1);
                if (fail) assertSame(failure, assertThrows(IllegalArgumentException.class, () -> finish(coordinator)));
                else finish(coordinator);
                assertTrue(coordinator.finished());
            }
            assertEquals(fail ? 6 : 0, amount(first));
            assertEquals(fail ? 6 : 0, amount(second));
            assertEquals(fail ? 6 : 0, Objects.requireNonNull(first.input()).availableAmount(MATERIAL));
        }
    }

    private record ChangingSource(ResourceStorage backing) implements ResourceStorage {
        @Override
        public StorageContext context() {
            return backing.context();
        }

        @Override
        public StorageSnapshot snapshot() {
            return backing.snapshot();
        }

        @Override
        public long amount(AEKey key) {
            return backing.amount(key);
        }

        @Override
        public long availableAmount(AEKey key) {
            return backing.availableAmount(key);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode) {
            return backing.insert(key, amount, mode);
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode) {
            return backing.extract(key, amount, mode);
        }

        @Override
        public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            backing.insert(key, 1, Actionable.MODULATE);
            return backing.reserveExtract(snapshot, key, amount);
        }

        @Override
        public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            return backing.reserveInsert(snapshot, key, amount);
        }
    }

    private static final class LocatedEnergy extends EnergyHatchBlockEntity {
        private LocatedEnergy() {
            super(HatchMode.MIXED, HatchTier.LV1);
        }

        @Override
        protected String allocationLocation() {
            return "identity_test:energy/" + getPos().toLong();
        }
    }

    /** Counts actual native comparisons with empty slots; no hash calls or elapsed-time thresholds. */
    private static final class SlotVisitKey extends AEKey {
        private final Key delegate = new Key(TYPE, "sparse_slot");
        private int emptySlotVisits;

        @Override
        public AEKeyType getType() {
            return TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public Object getPrimaryKey() {
            return delegate.getPrimaryKey();
        }

        @Override
        public ResourceLocation getId() {
            return delegate.getId();
        }

        @Override
        public Object getReadOnlyStack() {
            return this;
        }

        @Override
        protected ITextComponent computeDisplayName() {
            return delegate.getDisplayName();
        }

        @Override
        public NBTBase get(String componentId) {
            return null;
        }

        @Override
        public void writeToPacket(PacketBuffer data) {
            delegate.writeToPacket(data);
        }

        @Override
        public NBTTagCompound toTag() {
            return delegate.toTag();
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) emptySlotVisits++;
            return other instanceof SlotVisitKey key && delegate.equals(key.delegate);
        }
    }

    private static final class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final ExecutorService firstSource = Executors.newSingleThreadExecutor();
        private final ExecutorService secondSource = Executors.newSingleThreadExecutor();
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{firstSource, secondSource});

        @Override
        public void close() {
            workers.close();
            firstSource.close();
            secondSource.close();
        }
    }
}
