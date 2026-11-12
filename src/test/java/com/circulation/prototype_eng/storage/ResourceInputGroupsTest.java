package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.persistence.AttributeModifierNbt;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceRecipeObserver;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceInputGroups;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceInputGroupsTest {
    private static final KeyType TYPE = new KeyType("grouped_input");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final Key CATALYST = new Key(TYPE, "catalyst");
    private static final Key DYNAMIC = new Key(TYPE, "dynamic");

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 1000);
        return new SlottedResourceBuffer(2, capacities, () -> {});
    }

    private static MachineRecipe recipe(String name, int duration, long start, long perTick) {
        var io = new ResourceRequirement(List.of(new GenericStack(MATERIAL, start)),
            perTick == 0 ? List.of() : List.of(new GenericStack(MATERIAL, perTick)),
            List.of(new GenericStack(PRODUCT, 1)), List.of(new GenericStack(PRODUCT, 2)), false);
        return new MachineRecipe(id(name), duration, List.of(RecipeRequirements.RESOURCES.requirement(id(name + "_io"), io)));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("grouped_input_test", path);
    }

    private static RecipeCandidateIndex.Cursor lookup(Fixture fixture, MachineRecipe recipe) {
        var index = ResourceRecipeIndex.forFixedInputs(List.of(recipe), _ -> true);
        var cursor = index.beginLookup(fixture.machine, Set.of(), List.of(recipe));
        assertTrue(cursor.advance(new RecipeWorkBudget(Long.MAX_VALUE)));
        return cursor;
    }

    private static RecipeScanner.Result scan(Fixture fixture, MachineRecipe recipe) {
        var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool_" + recipe.id().getPath()), "pool", List.of(recipe))));
        var scanner = new RecipeScanner(fixture.host, catalog, () -> 1L);
        RecipeScanner.Result result = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            result = scanner.scan(new ScanLimits(20, 20));
            if (result.status() != RecipeScanner.Status.YIELDED) return result;
        }
        throw new AssertionError("Grouped scanner did not finish: " + result);
    }

    @Test
    void differentColorsCannotCombinePartialInputsButSameColorCanAdmitAndPayOneRecipe() {
        var red = new Port(AEColor.RED);
        var blue = new Port(AEColor.BLUE);
        red.input.insert(MATERIAL, 2, Actionable.MODULATE);
        blue.input.insert(MATERIAL, 2, Actionable.MODULATE);
        var fixture = new Fixture(red, blue);
        var recipe = recipe("split_colors", 1, 4, 0);
        assertTrue(Objects.requireNonNull(lookup(fixture, recipe).result()).isEmpty());
        assertEquals(RecipeScanner.Status.EXHAUSTED, scan(fixture, recipe).status());
        assertEquals(2, red.input.amount(MATERIAL));
        assertEquals(2, blue.input.amount(MATERIAL));

        blue.color = AEColor.RED;
        var admitted = scan(fixture, recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(new ResourceInputGroup(AEColor.RED), process.selectedInputGroup());
        process.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, red.input.amount(MATERIAL) + blue.input.amount(MATERIAL));
        assertEquals(3, red.output.amount(PRODUCT) + blue.output.amount(PRODUCT));
    }

    @Test
    void uncoloredInventoryIsItsOwnGroupAndCanBeReadWithEitherColoredGroup() {
        var uncolored = new Port(AEColor.TRANSPARENT);
        var red = new Port(AEColor.RED);
        var blue = new Port(AEColor.BLUE);
        uncolored.input.insert(MATERIAL, 2, Actionable.MODULATE);
        red.input.insert(MATERIAL, 2, Actionable.MODULATE);
        blue.input.insert(MATERIAL, 2, Actionable.MODULATE);
        var fixture = new Fixture(uncolored, uncolored, red, blue);
        var recipe = recipe("uncolored_generic", 1, 4, 0);

        var groups = MachineHatches.get(fixture.machine).inputGroups();
        assertNotNull(groups.find(ResourceInputGroup.TRANSPARENT));
        assertNotNull(groups.find(ResourceInputGroup.RED));
        assertNotNull(groups.find(new ResourceInputGroup(AEColor.BLUE)));
        var proof = lookup(fixture, recipe);
        assertEquals(List.of(recipe), proof.result());

        var admitted = scan(fixture, recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(new ResourceInputGroup(AEColor.RED), process.selectedInputGroup());
        process.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, uncolored.input.amount(MATERIAL));
        assertTrue(fixture.host.detach(process));
        assertEquals(0, red.input.amount(MATERIAL));
        assertEquals(2, blue.input.amount(MATERIAL));
        assertEquals(0, red.output.amount(PRODUCT));
        assertEquals(3, uncolored.output.amount(PRODUCT));
        assertEquals(0, blue.output.amount(PRODUCT));
        var validation = proof.beginValidation();
        assertTrue(validation.advance(new RecipeWorkBudget(Long.MAX_VALUE)));
        assertFalse(validation.current());
        assertEquals(RecipeScanner.Status.EXHAUSTED, scan(fixture, recipe).status());
        uncolored.input.insert(MATERIAL, 2, Actionable.MODULATE);
        var second = scan(fixture, recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, second.status());
        var blueProcess = Objects.requireNonNull(second.process());
        assertEquals(new ResourceInputGroup(AEColor.BLUE), blueProcess.selectedInputGroup());
        blueProcess.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, blueProcess.state().lifecycle());
        assertEquals(0, uncolored.input.amount(MATERIAL));
        assertEquals(0, blue.input.amount(MATERIAL));
        assertEquals(6, uncolored.output.amount(PRODUCT));
    }

    @Test
    void overlappingColoredStartsCannotReserveTheSameUncoloredBalanceTwice() {
        var uncolored = new Port(AEColor.TRANSPARENT);
        var red = new Port(AEColor.RED);
        var blue = new Port(AEColor.BLUE);
        uncolored.input.insert(MATERIAL, 2, Actionable.MODULATE);
        red.input.insert(MATERIAL, 2, Actionable.MODULATE);
        blue.input.insert(MATERIAL, 2, Actionable.MODULATE);
        var first = new Fixture(uncolored, red);
        var second = new Fixture(uncolored, blue);
        var recipe = recipe("shared_uncolored_reservation", 1, 4, 0);
        var redProcess = new RecipeProcess(first.machine, recipe, 1);
        var blueProcess = new RecipeProcess(second.machine, recipe, 2);
        redProcess.selectInputGroup(Objects.requireNonNull(MachineHatches.get(first.machine).inputGroups().find(new ResourceInputGroup(AEColor.RED))));
        blueProcess.selectInputGroup(Objects.requireNonNull(MachineHatches.get(second.machine).inputGroups().find(new ResourceInputGroup(AEColor.BLUE))));
        assertEquals(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(redProcess));
        assertEquals(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(blueProcess));
        assertTrue(first.host.start(redProcess));
        assertTrue(second.host.start(blueProcess));
        var redTick = Objects.requireNonNull(redProcess.beginTick());
        var blueTick = Objects.requireNonNull(blueProcess.beginTick());
        var redStart = Objects.requireNonNull(redTick.nextStage());
        var blueStart = Objects.requireNonNull(blueTick.nextStage());
        redStart.acquire();
        blueStart.acquire();
        assertInstanceOf(ProcessResult.Blocked.class, blueStart.settleSynchronously());
        assertEquals(ProcessResult.SUCCESS, redStart.settleSynchronously());
        redTick.finish();
        blueTick.finish();
        assertEquals(0, uncolored.input.amount(MATERIAL));
        assertEquals(0, red.input.amount(MATERIAL));
        assertEquals(2, blue.input.amount(MATERIAL));
        assertTrue(blue.receipts.isEmpty());
        assertEquals(1, uncolored.receipts.size());
        assertEquals(2, uncolored.receipts.getFirst().amounts().getLong(MATERIAL));
    }

    @Test
    void admittedBlueProcessKeepsItsGroupThroughStartTickEndAndBlocksInsteadOfSwitchingAfterRecolor() {
        var red = new Port(AEColor.RED);
        var blue = new Port(AEColor.BLUE);
        red.input.insert(MATERIAL, 2, Actionable.MODULATE);
        blue.input.insert(MATERIAL, 10, Actionable.MODULATE);
        var fixture = new Fixture(red, blue);
        var recipe = recipe("run_blue", 2, 3, 1);
        var admitted = scan(fixture, recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(new ResourceInputGroup(AEColor.BLUE), process.selectedInputGroup());
        process.tick();
        assertEquals(6, blue.input.amount(MATERIAL));
        assertEquals(1, blue.output.amount(PRODUCT));
        assertEquals(0, red.output.amount(PRODUCT));

        red.input.insert(MATERIAL, 100, Actionable.MODULATE);
        blue.color = AEColor.RED;
        process.tick();
        assertInstanceOf(ProcessResult.Blocked.class, process.state().result());
        assertEquals(6, blue.input.amount(MATERIAL));
        assertEquals(102, red.input.amount(MATERIAL));
        assertEquals(1, blue.output.amount(PRODUCT));
        assertEquals(new ResourceInputGroup(AEColor.BLUE), process.selectedInputGroup());

        blue.color = AEColor.BLUE;
        process.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(5, blue.input.amount(MATERIAL));
        assertEquals(4, blue.output.amount(PRODUCT));
        assertEquals(0, red.output.amount(PRODUCT));
    }

    @Test
    void groupedLookupProofExpiresAfterInventoryMutationAndColorChange() {
        var red = new Port(AEColor.RED);
        var blue = new Port(AEColor.BLUE);
        red.input.insert(MATERIAL, 4, Actionable.MODULATE);
        var fixture = new Fixture(red, blue);
        var recipe = recipe("proof", 1, 4, 0);
        var first = lookup(fixture, recipe);
        assertEquals(List.of(recipe), first.result());
        assertTrue(first.current());
        red.input.extract(MATERIAL, 1, Actionable.MODULATE);
        var validation = first.beginValidation();
        assertTrue(validation.advance(new RecipeWorkBudget(Long.MAX_VALUE)));
        assertFalse(validation.current());
        var insufficient = lookup(fixture, recipe);
        assertTrue(Objects.requireNonNull(insufficient.result()).isEmpty());
        blue.input.insert(MATERIAL, 1, Actionable.MODULATE);
        blue.color = AEColor.RED;
        assertFalse(insufficient.current());
        var combined = lookup(fixture, recipe);
        assertEquals(List.of(recipe), combined.result());
        red.color = AEColor.BLUE;
        assertFalse(combined.current());
    }

    @Test
    void savedSelectedGroupResumesAgainstThatColorAndMissingColorDoesNotBorrowAnotherGroupsInventory() {
        var red = new Port(AEColor.RED);
        var blue = new Port(AEColor.BLUE);
        red.input.insert(MATERIAL, 2, Actionable.MODULATE);
        blue.input.insert(MATERIAL, 10, Actionable.MODULATE);
        var fixture = new Fixture(red, blue);
        var recipe = recipe("restore_blue", 2, 3, 1);
        var admitted = scan(fixture, recipe);
        var original = Objects.requireNonNull(admitted.process());
        original.tick();
        var keys = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);
        var codec = RecipeProcessNbt.builder(recipe, "group-1").resources(keys).attributes(new AttributeModifierNbt()).build();
        var saved = codec.encode(original);
        var lateFixture = new Fixture();
        var late = codec.decode(lateFixture.machine, saved);
        assertInstanceOf(ProcessResult.Blocked.class, late.tick());
        var lateBlue = new Port(AEColor.BLUE);
        lateBlue.input.insert(MATERIAL, 6, Actionable.MODULATE);
        lateFixture.machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(lateBlue), List.of(), List.of()));
        late.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, late.state().lifecycle());
        assertEquals(5, lateBlue.input.amount(MATERIAL));
        assertEquals(3, lateBlue.output.amount(PRODUCT));
        var restored = codec.decode(fixture.machine, saved);
        assertEquals(new ResourceInputGroup(AEColor.BLUE), restored.selectedInputGroup());
        restored.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, restored.state().lifecycle());
        assertEquals(5, blue.input.amount(MATERIAL));
        assertEquals(4, blue.output.amount(PRODUCT));

        blue.color = AEColor.RED;
        red.input.insert(MATERIAL, 100, Actionable.MODULATE);
        var missing = codec.decode(fixture.machine, saved);
        assertEquals(new ResourceInputGroup(AEColor.BLUE), missing.selectedInputGroup());
        assertInstanceOf(ProcessResult.Blocked.class, missing.tick());
        assertEquals(102, red.input.amount(MATERIAL));
        assertEquals(5, blue.input.amount(MATERIAL));
        assertEquals(4, blue.output.amount(PRODUCT));
    }

    @Test
    void observerPublishesOnlyAcceptedAttachAndCommittedStagesAndDetachesExactlyOnce() {
        var red = new Port(AEColor.RED);
        red.input.insert(MATERIAL, 4, Actionable.MODULATE);
        var fixture = new Fixture(red, red);
        var recipe = recipe("observer", 1, 4, 0);
        var admitted = scan(fixture, recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(List.of("attached"), red.events);
        assertEquals(0, red.committedStages.size());
        assertTrue(red.receipts.isEmpty(), "Scanner probes must not publish source receipts");
        var result = process.tick();
        assertEquals(ProcessResult.SUCCESS, result);
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), red.committedStages);
        assertEquals(1, red.receipts.size(), "Aliased observer views must publish one aggregated receipt");
        assertEquals(RecipeStage.START, red.receipts.getFirst().stage());
        assertEquals(4, red.receipts.getFirst().amounts().getLong(MATERIAL));
        assertTrue(fixture.host.detach(process));
        assertEquals(List.of("attached", "detached"), red.events);
    }

    @Test
    void rejectedAndRolledBackStartNeverPublishStageCommit() {
        var red = new Port(AEColor.RED);
        var fixture = new Fixture(red);
        var blockedRecipe = recipe("observer_blocked", 1, 4, 0);
        var blocked = new RecipeProcess(fixture.machine, blockedRecipe, 1);
        blocked.selectInputGroup(ResourceInputGroups.capture(MachineHatches.get(fixture.machine)).groups().getFirst());
        assertTrue(fixture.host.start(blocked));
        assertInstanceOf(ProcessResult.Blocked.class, blocked.tick());
        assertTrue(red.committedStages.isEmpty());
        assertTrue(red.receipts.isEmpty());
        assertTrue(fixture.host.detach(blocked));

        red.input.insert(MATERIAL, 4, Actionable.MODULATE);
        int[] reversals = {0};
        var fail = new RecipeRequirementType<Boolean>(id("observer_rollback_type"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return new RejectedStart(reversals);
            }
        });
        var io = RecipeRequirements.RESOURCES.requirement(id("observer_native_io"),
            new ResourceRequirement(List.of(new GenericStack(MATERIAL, 4)), List.of()));
        var failingRecipe = new MachineRecipe(id("observer_rollback"), 1, List.of(io, fail.requirement(id("fail"), true)));
        var failing = new RecipeProcess(fixture.machine, failingRecipe, 2);
        failing.selectInputGroup(ResourceInputGroups.capture(MachineHatches.get(fixture.machine)).groups().getFirst());
        assertTrue(fixture.host.start(failing));
        assertThrows(IllegalStateException.class, failing::tick);
        assertEquals(4, red.input.amount(MATERIAL));
        assertEquals(1, reversals[0]);
        assertTrue(red.committedStages.isEmpty());
        assertTrue(red.receipts.isEmpty());
        assertThrows(IllegalStateException.class, () -> fixture.host.detach(failing), "A faulted process retains its lifecycle boundary");
        assertEquals(List.of("attached", "detached", "attached"), red.events);
    }

    private record RejectedStart(int[] reversals) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
            throw new IllegalStateException("Rejected after native resource application");
        }

        @Override
        public void rollback() {
            reversals[0]++;
        }
    }

    @Test
    void sharedCatalystJoinsEachPatternPartitionButTwoPatternInputBuffersNeverCombine() {
        var first = new Port(AEColor.RED, "provider/pattern0", false);
        var second = new Port(AEColor.RED, "provider/pattern1", false);
        var catalyst = new Port(AEColor.RED, "provider/", true);
        first.input.insert(MATERIAL, 2, Actionable.MODULATE);
        second.input.insert(MATERIAL, 2, Actionable.MODULATE);
        catalyst.input.insert(CATALYST, 2, Actionable.MODULATE);
        var provider = new ResourceHatch() {
            @Override
            public ResourceStorage input() {
                return null;
            }

            @Override
            public ResourceStorage output() {
                return null;
            }

            @Override
            public List<? extends ResourceHatch> inputPartitions() {
                return List.of(first, second, catalyst);
            }

            @Override
            public boolean isValid() {
                return true;
            }
        };
        var fixture = new Fixture(provider);
        var separated = new MachineRecipe(id("pattern_separated"), 1, List.of(RecipeRequirements.RESOURCES.requirement(id("partition_io"),
            new ResourceRequirement(List.of(new GenericStack(MATERIAL, 4), new GenericStack(CATALYST, 1)), List.of()))));
        assertTrue(Objects.requireNonNull(lookup(fixture, separated).result()).isEmpty());
        assertEquals(RecipeScanner.Status.EXHAUSTED, scan(fixture, separated).status());

        var shared = new MachineRecipe(id("pattern_shared"), 1, List.of(RecipeRequirements.RESOURCES.requirement(id("shared_io"),
            new ResourceRequirement(List.of(new GenericStack(MATERIAL, 2), new GenericStack(CATALYST, 1)), List.of()))));
        var admitted = scan(fixture, shared);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(new ResourceInputGroup(AEColor.RED, "provider/pattern0"), process.selectedInputGroup());
        process.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, first.input.amount(MATERIAL));
        assertEquals(2, second.input.amount(MATERIAL));
        assertEquals(1, catalyst.input.amount(CATALYST));
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), first.committedStages);
        assertTrue(second.committedStages.isEmpty());
        assertEquals(1, first.receipts.size());
        assertEquals(2, first.receipts.getFirst().amounts().getLong(MATERIAL));
        assertTrue(second.receipts.isEmpty(), "The unused same-color pattern must never lock");
        assertEquals(1, catalyst.receipts.size());
        assertEquals(1, catalyst.receipts.getFirst().amounts().getLong(CATALYST));
    }

    @Test
    void receiptsReportActualFuzzyAndOverlayKeysAtTheFinalChanceAndParallelQuantity() {
        var red = new Port(AEColor.RED);
        var dynamic = new Port(AEColor.RED);
        dynamic.observer = red.observer;
        red.input.insert(CATALYST, 50, Actionable.MODULATE);
        red.input.insert(PRODUCT, 50, Actionable.MODULATE);
        dynamic.input.insert(DYNAMIC, 50, Actionable.MODULATE);
        var fixture = new Fixture(red, dynamic);
        fixture.machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
        var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 2)), List.of(), List.of(), List.of(), false,
            new ResourceChance(200, null), null, List.of(new FuzzyInputGroup(List.of(MATERIAL, CATALYST), 3)),
            List.of(new DynamicInputGroup(new NamedResourceMatcher(id("dynamic_receipt_matcher"), (candidate, _) -> candidate.equals(DYNAMIC)),
                MATERIAL, 4, List.of(MATERIAL), 4)));
        var io = RecipeRequirements.RESOURCES.requirement(id("resolved_receipt_io"), payload);
        var recipe = new MachineRecipe(id("resolved_receipt"), 1, List.of(io));
        var process = new RecipeProcess(fixture.machine, recipe, 9);
        RecipeOverlays.getOrCreate(process).put(io, "actual", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(List.of(new GenericStack(PRODUCT, 2)), null, null, null, null)).complete();
        process.selectInputGroup(ResourceInputGroups.capture(MachineHatches.get(fixture.machine)).groups().getFirst());
        assertEquals(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        assertTrue(red.receipts.isEmpty());
        assertTrue(fixture.host.start(process));
        process.tick();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(1, red.receipts.size());
        var amounts = red.receipts.getFirst().amounts();
        assertEquals(0, amounts.getLong(MATERIAL));
        assertEquals(8, amounts.getLong(PRODUCT));
        assertEquals(12, amounts.getLong(CATALYST));
        assertEquals(16, amounts.getLong(DYNAMIC));
        assertEquals(3, amounts.size());
        assertTrue(dynamic.receipts.isEmpty(), "Equal observer identities merge distinct physical source receipts once");
        assertThrows(UnsupportedOperationException.class, () -> amounts.addTo(PRODUCT, 1));
    }

    @Test
    void oneCreditPreparedSourceSettlementPublishesReceiptOnlyAfterTheWholeStageReleasesItsFence() {
        var red = new Port(AEColor.RED);
        red.input.insert(MATERIAL, 8, Actionable.MODULATE);
        var fixture = new Fixture(red);
        var recipe = recipe("budget_receipt", 2, 4, 0);
        var process = new RecipeProcess(fixture.machine, recipe, 1);
        process.selectInputGroup(ResourceInputGroups.capture(MachineHatches.get(fixture.machine)).groups().getFirst());
        assertTrue(fixture.host.start(process));
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        stage.acquirePreparedWithinBudget(new RecipeWorkBudget(1));
        for (int step = 0; step < 10000 && stage.preparationPending(); step++) {
            var budget = new RecipeWorkBudget(1);
            var computation = stage.computation();
            if (computation != null && computation.advance(budget)) stage.retainPreparedFacts(computation);
            stage.resumePreparedWithinBudget(budget);
        }
        assertFalse(stage.preparationPending());
        drain(() -> stage.finishCapturedInventoryWithinBudget(new RecipeWorkBudget(1)));
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage)));
        for (var source : batch.sources()) drain(() -> {
            assertTrue(source.applyWithinBudget(new RecipeWorkBudget(1)));
            return source.state() == ResourceBatch.SourceState.APPLIED;
        });
        assertTrue(red.receipts.isEmpty());
        drain(() -> batch.decision() == ResourceBatch.Decision.OPEN
            ? batch.commitWithinBudget(() -> true, new RecipeWorkBudget(1))
            : batch.resolveBusinessWithinBudget(new RecipeWorkBudget(1)));
        for (var source : batch.sources()) drain(() -> source.resolveWithinBudget(new RecipeWorkBudget(1)));
        assertTrue(red.receipts.isEmpty(), "Completed source tokens cannot publish before the outer stage settles");
        drain(() -> batch.finishWithinBudget(new RecipeWorkBudget(1)));
        assertTrue(stage.settleBatch(null));
        assertEquals(1, red.receipts.size());
        assertEquals(4, red.receipts.getFirst().amounts().getLong(MATERIAL));
        assertEquals(4, red.input.amount(MATERIAL));
        session.finish();
    }

    @Test
    void canonicalRecipePriorityWinsOverColorEnumerationInBothIndexesAndScanner() {
        var red = new Port(AEColor.RED);
        var blue = new Port(AEColor.BLUE);
        red.input.insert(MATERIAL, 1, Actionable.MODULATE);
        blue.input.insert(CATALYST, 1, Actionable.MODULATE);
        var fixture = new Fixture(red, blue);
        var earlier = SimpleRecipePool.Entry.create(id("earlier_blue"), 1, new GenericStack(CATALYST, 1), List.of(), Set.of());
        var later = SimpleRecipePool.Entry.create(id("later_red"), 1, new GenericStack(MATERIAL, 1), List.of(), Set.of());
        var canonical = List.of(earlier.recipe(), later.recipe());
        var index = ResourceRecipeIndex.forFixedInputs(canonical, _ -> true);
        var indexed = index.beginLookup(fixture.machine, Set.of(), canonical);
        drain(() -> indexed.advance(new RecipeWorkBudget(1)));
        assertEquals(canonical, indexed.result());
        var pool = new SimpleRecipePool(id("ordered_pool"), "ordered.pool", List.of(earlier, later), _ -> true);
        var simple = Objects.requireNonNull(pool.beginCandidates(fixture.machine, Set.of()));
        drain(() -> simple.advance(new RecipeWorkBudget(1)));
        assertEquals(canonical, simple.result());
        var scanner = new RecipeScanner(fixture.host, new RecipeCatalog(List.of(pool)), () -> 1L);
        RecipeScanner.Result admitted = scanner.scan(new ScanLimits(20, 20));
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(earlier.recipe(), process.recipe());
        assertEquals(new ResourceInputGroup(AEColor.BLUE), process.selectedInputGroup());
    }

    @Test
    void allSourceSnapshotPrecedesObserverReceiptAndNeverSurvivesABlockedLaterStage() {
        var ordinary = buffer();
        ordinary.insert(MATERIAL, 2, Actionable.MODULATE);
        ordinary.insert(CATALYST, 1, Actionable.MODULATE);
        var observed = new Port(AEColor.RED);
        observed.input.insert(MATERIAL, 2, Actionable.MODULATE);
        var fixture = new Fixture(new PlainPort(ordinary), observed);
        var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 4), new GenericStack(CATALYST, 1)),
            List.of(new GenericStack(MATERIAL, 1)), List.of(), List.of(), false);
        var recipe = new MachineRecipe(id("all_sources"), 2,
            List.of(RecipeRequirements.RESOURCES.requirement(id("all_sources_io"), payload)));
        var process = new RecipeProcess(fixture.machine, recipe, 1);
        assertEquals(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        assertNull(process.committedInputs(RecipeStage.START));
        assertTrue(fixture.host.start(process));
        var session = Objects.requireNonNull(process.beginTick());
        var start = Objects.requireNonNull(session.nextStage());
        start.acquire();
        assertEquals(ProcessResult.SUCCESS, start.settleSynchronously());
        assertEquals(1, observed.receipts.size());
        var source = observed.receipts.getFirst();
        assertEquals(2, source.amounts().getLong(MATERIAL));
        var complete = Objects.requireNonNull(source.allInputs());
        assertEquals(4, complete.getLong(MATERIAL));
        assertEquals(1, complete.getLong(CATALYST));
        assertEquals(2, complete.size());
        assertThrows(UnsupportedOperationException.class, () -> complete.addTo(MATERIAL, 1));
        var tick = Objects.requireNonNull(session.nextStage());
        assertNull(process.committedInputs(RecipeStage.START));
        tick.acquire();
        assertInstanceOf(ProcessResult.Blocked.class, tick.settleSynchronously());
        assertNull(process.committedInputs(RecipeStage.TICK));
        assertEquals(1, observed.receipts.size());
        session.finish();
    }

    @Test
    void crossSourceLongOverflowDoesNotRejectLegalNativeReservationsAndExactLongMaxStaysAvailable() {
        for (long secondAmount : new long[] {1, 2}) {
            var first = new UnboundedResourceBuffer(() -> {});
            var second = new UnboundedResourceBuffer(() -> {});
            first.insert(MATERIAL, Long.MAX_VALUE - 1, Actionable.MODULATE);
            second.insert(MATERIAL, secondAmount, Actionable.MODULATE);
            var fixture = new Fixture(new PlainPort(first), new PlainPort(second));
            var type = new RecipeRequirementType<long[]>(id("large_native_inputs_" + secondAmount), new RecipeRequirementSystem<>() {
                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<long[]> requirement) {
                    var inputs = process.hatches().resources();
                    var tokens = new ObjectArrayList<ResourceReservation>();
                    for (int index = 0; index < inputs.size(); index++) {
                        var input = Objects.requireNonNull(inputs.get(index).input());
                        var reserved = input.reserveExtract(input.snapshot(), MATERIAL, requirement.payload()[index]);
                        assertEquals(ReservationResult.Status.ACCEPTED, reserved.status());
                        tokens.add(Objects.requireNonNull(reserved.reservation()));
                    }
                    return new NativeInputs(tokens);
                }
            });
            var recipe = new MachineRecipe(id("large_native_stage_" + secondAmount), 2,
                List.of(type.requirement(id("large_inputs"), new long[] {Long.MAX_VALUE - 1, secondAmount})));
            var process = new RecipeProcess(fixture.machine, recipe, 1);
            assertTrue(fixture.host.start(process));
            var session = Objects.requireNonNull(process.beginTick());
            var start = Objects.requireNonNull(session.nextStage());
            start.acquire();
            assertEquals(ProcessResult.SUCCESS, start.settleSynchronously());
            assertEquals(0, first.amount(MATERIAL));
            assertEquals(0, second.amount(MATERIAL));
            var complete = process.committedInputs(RecipeStage.START);
            if (secondAmount == 1) assertEquals(Long.MAX_VALUE, Objects.requireNonNull(complete).getLong(MATERIAL));
            else assertNull(complete, "Unrepresentable totals must not wrap or reject the committed stage");
            session.finish();
        }
    }

    private record NativeInputs(List<ResourceReservation> reservations) implements RequirementTransaction {
        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public void commit() {}
        @Override public void rollback() {}
    }

    private record PlainPort(ResourceStorage input) implements ResourceHatch {
        @Override public ResourceStorage output() { return null; }
        @Override public boolean isValid() { return input.isValid(); }
    }

    private static void drain(BooleanSupplier advance) {
        for (int step = 0; step < 10000; step++) if (advance.getAsBoolean()) return;
        throw new AssertionError("One-credit source work did not finish");
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final MachineWorkSlots host;

        private Fixture(ResourceHatch... ports) {
            machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
            machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(ports), List.of(), List.of()));
            host = new MachineWorkSlots(machine, List.of());
            machine.components().put(MachineWorkSlots.TYPE, host);
        }
    }

    private static final class Port implements ResourceHatch, ResourceRecipeObserver {
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private AEColor color;
        private Object observer = this;
        private final String partition;
        private final boolean shared;
        private final List<String> events = new ObjectArrayList<>();
        private final List<RecipeStage> committedStages = new ObjectArrayList<>();
        private final List<InputReceipt> receipts = new ObjectArrayList<>();

        private Port(AEColor color) {
            this(color, "", false);
        }

        private Port(AEColor color, String partition, boolean shared) {
            this.color = color;
            this.partition = partition;
            this.shared = shared;
        }

        @Override
        public ResourceInputGroup inputGroup() {
            return new ResourceInputGroup(color, partition);
        }

        @Override
        public Object observerIdentity() {
            return observer;
        }

        @Override
        public boolean sharedInput() {
            return shared;
        }

        @Override
        public ResourceStorage input() {
            return input;
        }

        @Override
        public ResourceStorage output() {
            return output;
        }

        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }

        @Override
        public void attached(RecipeProcess process) {
            events.add("attached");
        }

        @Override
        public void detached(RecipeProcess process) {
            events.add("detached");
        }

        @Override
        public void stageCommitted(RecipeProcess process, RecipeStage stage) {
            committedStages.add(stage);
        }

        @Override
        public void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
            assertThrows(IllegalStateException.class, input.context()::requireLock, "Source callback must run after storage locks exit");
            receipts.add(new InputReceipt(stage, amounts, process.committedInputs(stage)));
        }
    }

    private record InputReceipt(RecipeStage stage, AEKey2LongMap amounts, AEKey2LongMap allInputs) {}
}
