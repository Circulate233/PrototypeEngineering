package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.energy.EnergySnapshot;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementContinuation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CheckpointAlternativeExecutionTest {
    private static final KeyType TYPE = new KeyType("checkpoint_alternative");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final Key RESULT = new Key(TYPE, "result");
    private static final ResourceLocation SCORE = id("score");

    private static void assertFallback(RecipeStage phase, ProcessResult result) {
        var fixture = new Fixture();
        var rejectedEffects = new PhaseEffects();
        var fallbackStarts = new AtomicInteger();
        var selection = selector("choice", List.of(
            rejectingCandidate("rejected", fixture, rejectedEffects, phase, result, 100, 100),
            succeedingCandidate("accepted", external("fallback_boundary", (_, stage) -> {
                if (stage == RecipeStage.START) {
                    fallbackStarts.incrementAndGet();
                    fixture.assertContents(100, 100, 0);
                    assertEquals(0, rejectedEffects.active.get(), "Candidate business effects must be compensated first");
                }
                return RequirementTransaction.SUCCESS;
            }))));
        var process = process(fixture.machine, 0, selection);
        assertSame(ProcessResult.SUCCESS, process.tick());
        fixture.assertContents(90, 80, 20);
        assertEquals(1, fallbackStarts.get());
        assertEquals(0, rejectedEffects.active.get());
        assertEquals(phase == RecipeStage.TICK ? 2 : 3, rejectedEffects.commits.get());
        assertEquals(phase == RecipeStage.TICK ? 2 : 3, rejectedEffects.rollbacks.get());
        assertEquals(1, decision(process, selection.path(null)).index());
        assertTrue(decision(process, selection.path(null)).completed());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, fallbackStarts.get(), "Completed candidates cannot replay");
    }

    private static RecipeRequirement<CheckpointRequirement> rejectingCandidate(String name, Fixture fixture,
                                                                               PhaseEffects effects, RecipeStage rejectedPhase, ProcessResult result, long initialEnergy, long initialMaterial) {
        return checkpoint(name, sequence(name + "_cycle", energy(name + "_energy", 30, 10, 10),
            resources(name + "_material", List.of(stack(MATERIAL, 40)), List.of(stack(PRODUCT, 15)), List.of(stack(PRODUCT, 25))),
            checkpoint(name + "_start_output", resources(name + "_produce", List.of(), List.of(stack(PRODUCT, 10)), List.of(stack(PRODUCT, 10)))),
            effects.requirement(name + "_business"), external(name + "_gate", (_, phase) -> {
                fixture.assertContents(initialEnergy - (phase == RecipeStage.TICK ? 40 : 30), initialMaterial - 40,
                    phase == RecipeStage.START ? 20 : phase == RecipeStage.TICK ? 35 : 60);
                return phase == rejectedPhase ? rejected(result) : RequirementTransaction.SUCCESS;
            })));
    }

    private static RecipeRequirement<CheckpointRequirement> succeedingCandidate(String name, RecipeRequirement<?> observer) {
        return checkpoint(name, sequence(name + "_cycle", observer, energy(name + "_energy", 10, 5, 5),
            resources(name + "_material", List.of(stack(MATERIAL, 20)), List.of(stack(PRODUCT, 5)), List.of(stack(PRODUCT, 15)))));
    }

    /**
     * Only orders real child acquisitions; storage effects remain owned by builtin requirements.
     */
    private static RecipeRequirement<List<RecipeRequirement<?>>> sequence(String name, RecipeRequirement<?>... children) {
        var system = new RecipeRequirementSystem<List<RecipeRequirement<?>>>() {
            @Override
            public List<RecipeRequirement<?>> children(List<RecipeRequirement<?>> payload) {
                return payload;
            }

            @Override
            public boolean continuedAcquisition(List<RecipeRequirement<?>> payload) {
                return true;
            }

            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<List<RecipeRequirement<?>>> requirement) {
                return new Sequence(process, requirement.payload(), RecipeStage.START);
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<List<RecipeRequirement<?>>> requirement) {
                return new Sequence(process, requirement.payload(), RecipeStage.TICK);
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<List<RecipeRequirement<?>>> requirement) {
                return new Sequence(process, requirement.payload(), RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(id(name + "_sequence"), system).requirement(id(name), List.of(children));
    }

    private static RecipeRequirement<CheckpointRequirement> checkpoint(String name, RecipeRequirement<?> child) {
        return RecipeRequirements.CHECKPOINT.requirement(id(name), new CheckpointRequirement(0, child));
    }

    private static RecipeRequirement<EnergyRequirement> energy(String name, long input, long tickInput, long output) {
        return RecipeRequirements.ENERGY.requirement(id(name), new EnergyRequirement(input, tickInput, 0, output, false));
    }

    private static RecipeRequirement<ResourceRequirement> resources(String name, List<GenericStack> input,
                                                                    List<GenericStack> tickOutput, List<GenericStack> output) {
        return RecipeRequirements.RESOURCES.requirement(id(name), new ResourceRequirement(input, List.of(), tickOutput, output, false));
    }

    private static RecipeRequirement<SelectiveRequirement> selector(String name, List<RecipeRequirement<?>> candidates) {
        return selector(name, candidates, List.of());
    }

    private static RecipeRequirement<SelectiveRequirement> selector(String name, List<RecipeRequirement<?>> candidates,
                                                                    List<SelectiveModifierDescriptor> modifiers) {
        return RecipeRequirements.SELECTIVE.requirement(id(name), new SelectiveRequirement(candidates, modifiers));
    }

    private static RecipeRequirement<String> external(String name, BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquire) {
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.START);
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.TICK);
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(new ResourceLocation("zz_checkpoint_alternative", name), system).requirement(id(name), "observer");
    }

    private static RequirementTransaction rejected(ProcessResult result) {
        return new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return result;
            }

            @Override
            public void commit() {
                fail("Rejected child cannot commit");
            }

            @Override
            public void rollback() {
            }
        };
    }

    private static SelectiveState.Selection decision(RecipeProcess process, RequirementPath path) {
        return Objects.requireNonNull(SelectiveState.getOrCreate(process).selection(path));
    }

    private static GenericStack stack(Key key, long amount) {
        return new GenericStack(key, amount);
    }

    private static RecipeProcess process(MachineInstance machine, int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(machine, new MachineRecipe(id("recipe"), duration, List.of(requirements)), 73);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("checkpoint_alternative_test", name);
    }

    private static PlannedDeadline deadline() {
        return PlannedDeadline.after(Duration.ofSeconds(10));
    }

    private static MachineInstance machine(SlottedResourceBuffer storage, EnergyBuffer energy, @Nullable SourceObservations observations) {
        var machine = new MachineInstance();
        List<ResourceHatch> resources = List.of(new LoopHatch(storage), new LoopHatch(storage));
        List<EnergyHatch> energies = observations == null ? List.of(energy, energy)
            : List.of(new ObservedEnergy(energy, observations), new ObservedEnergy(energy, observations));
        machine.components().put(MachineHatches.TYPE, new MachineHatches(resources, energies, energies));
        return machine;
    }

    @Test
    void aBlockedChildTickRefundsItsCandidateBeforeTheNextCheckpointStarts() {
        assertFallback(RecipeStage.TICK, new ProcessResult.Blocked("child tick blocked"));
    }

    @Test
    void aFailedChildTickRefundsItsCandidateBeforeTheNextCheckpointStarts() {
        assertFallback(RecipeStage.TICK, new ProcessResult.Failure("child tick failed"));
    }

    @Test
    void aBlockedChildEndRefundsItsCandidateBeforeTheNextCheckpointStarts() {
        assertFallback(RecipeStage.END, new ProcessResult.Blocked("child end blocked"));
    }

    @Test
    void aFailedChildEndRefundsItsCandidateBeforeTheNextCheckpointStarts() {
        assertFallback(RecipeStage.END, new ProcessResult.Failure("child end failed"));
    }

    @Test
    void allRejectedCheckpointsDisableTheSelectorAndLetEveryOuterStageContinue() {
        var fixture = new Fixture();
        var first = new PhaseEffects();
        var second = new PhaseEffects();
        var modifiers = new AtomicInteger();
        var outerPhases = new ObjectArrayList<RecipeStage>();
        var selection = selector("disabled", List.of(
                rejectingCandidate("blocked", fixture, first, RecipeStage.TICK, new ProcessResult.Blocked("missing"), 100, 100),
                rejectingCandidate("failed", fixture, second, RecipeStage.END, new ProcessResult.Failure("invalid"), 100, 100)),
            List.of(new SelectiveModifierDescriptor(id("unused"), _ -> modifiers.incrementAndGet())));
        var process = process(fixture.machine, 1, selection, external("outer_successor", (_, stage) -> {
            fixture.assertContents(100, 100, 0);
            assertEquals(0, first.active.get());
            assertEquals(0, second.active.get());
            outerPhases.add(stage);
            return RequirementTransaction.SUCCESS;
        }));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), outerPhases);
        assertEquals(-1, decision(process, selection.path(null)).index());
        assertTrue(decision(process, selection.path(null)).completed());
        assertEquals(0, modifiers.get());
        assertEquals(2, first.rollbacks.get());
        assertEquals(3, second.rollbacks.get());
        fixture.assertContents(100, 100, 0);
    }

    @Test
    void onlyTheSuccessfulCandidateInstallsItsModifierAndLaterStagesSeeItOnce() {
        var fixture = new Fixture();
        var rejectedEffects = new PhaseEffects();
        var modifierCalls = new AtomicInteger();
        var childPhases = new ObjectArrayList<RecipeStage>();
        var outerPhases = new ObjectArrayList<RecipeStage>();
        var selection = selector("modified", List.of(
            rejectingCandidate("rejected", fixture, rejectedEffects, RecipeStage.END, new ProcessResult.Failure("invalid"), 100, 100),
            succeedingCandidate("chosen", external("child_without_modifier", (current, phase) -> {
                assertEquals(1, current.attributes().value(SCORE), "The modifier follows the entire candidate START continuation");
                childPhases.add(phase);
                return RequirementTransaction.SUCCESS;
            }))), List.of(new SelectiveModifierDescriptor(id("bonus"), context -> {
            assertEquals(1, context.candidateIndex());
            fixture.assertContents(90, 80, 20);
            assertEquals(0, rejectedEffects.active.get());
            modifierCalls.incrementAndGet();
            context.putProcessAttributeModifier(SCORE, new NumericAttributeModifier("bonus", 4, AttributeModifier.Operation.ADDITION));
        })));
        var process = process(fixture.machine, 3, selection, external("outer_modified", (current, phase) -> {
            assertEquals(5, current.attributes().value(SCORE));
            outerPhases.add(phase);
            return RequirementTransaction.SUCCESS;
        }));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(5, process.attributes().value(SCORE));
        assertEquals(1, decision(process, selection.path(null)).modifiers().size());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, modifierCalls.get());
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), childPhases);
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.TICK, RecipeStage.TICK, RecipeStage.END), outerPhases);
        assertEquals(1, process.attributes().value(SCORE));
        assertTrue(decision(process, selection.path(null)).completed());
        fixture.assertContents(90, 80, 20);
    }

    @Test
    void aLaterOuterFailureAlsoCompensatesTheSuccessfulFallbackAndItsModifier() {
        var fixture = new Fixture();
        var rejectedEffects = new PhaseEffects();
        var acceptedEffects = new PhaseEffects();
        var calls = new AtomicInteger();
        var selection = selector("outer_failure", List.of(
                rejectingCandidate("rejected", fixture, rejectedEffects, RecipeStage.TICK, new ProcessResult.Blocked("missing"), 100, 100),
                succeedingCandidate("chosen", acceptedEffects.requirement("accepted_business"))),
            List.of(new SelectiveModifierDescriptor(id("bonus"), context -> {
                calls.incrementAndGet();
                context.putProcessAttributeModifier(SCORE, new NumericAttributeModifier("bonus", 4, AttributeModifier.Operation.ADDITION));
            })));
        var failure = new ProcessResult.Failure("outer successor failed");
        var process = process(fixture.machine, 0, selection, external("outer_failure_gate", (current, phase) -> {
            if (phase != RecipeStage.START) {
                return RequirementTransaction.SUCCESS;
            }
            fixture.assertContents(90, 80, 20);
            assertEquals(5, current.attributes().value(SCORE));
            assertEquals(3, acceptedEffects.active.get());
            return rejected(failure);
        }));
        assertSame(failure, process.tick());
        fixture.assertContents(100, 100, 0);
        assertEquals(0, acceptedEffects.active.get());
        assertEquals(3, acceptedEffects.rollbacks.get());
        assertEquals(0, rejectedEffects.active.get());
        assertEquals(1, calls.get());
        assertEquals(1, process.attributes().value(SCORE));
        assertNull(SelectiveState.getOrCreate(process).selection(selection.path(null)));
        assertEquals(RecipeProcess.Lifecycle.FAILED, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
    }

    @Test
    void reusedNestedSelectorsKeepExactPathsAndTheirSuccessfulCheckpointOutputFeedsTheNextInput() {
        var fixture = new Fixture();
        var visits = new ObjectArrayList<Visit>();
        var observer = external("nested_leaf", (current, phase) -> {
            visits.add(new Visit(current.requirementPath(), phase));
            if (phase == RecipeStage.TICK) {
                assertEquals(0, current.tickContext().indexAfter());
            }
            return RequirementTransaction.SUCCESS;
        });
        var innerCycle = sequence("inner_cycle", resources("produce", List.of(), List.of(stack(PRODUCT, 7)), List.of(stack(PRODUCT, 13))), observer);
        var innerCheckpoint = checkpoint("inner_checkpoint", innerCycle);
        var inner = selector("shared_inner", List.of(innerCheckpoint));
        var converter = checkpoint("converter", resources("convert", List.of(stack(PRODUCT, 20)),
            List.of(stack(RESULT, 8)), List.of(stack(RESULT, 12))));
        var rejectedGroup = sequence("rejected_group", inner, converter, external("nested_rejection", (_, phase) -> {
            assertEquals(20, fixture.storage.availableAmount(RESULT));
            return phase == RecipeStage.END ? rejected(new ProcessResult.Failure("nested candidate rejected")) : RequirementTransaction.SUCCESS;
        }));
        var acceptedGroup = sequence("accepted_group", external("nested_fallback_boundary", (_, phase) -> {
            if (phase == RecipeStage.START) {
                fixture.assertContents(100, 100, 0);
                assertEquals(0, fixture.storage.availableAmount(RESULT));
            }
            return RequirementTransaction.SUCCESS;
        }), inner, converter);
        var rejectedCheckpoint = checkpoint("rejected_checkpoint", rejectedGroup);
        var acceptedCheckpoint = checkpoint("accepted_checkpoint", acceptedGroup);
        var outer = selector("nested_outer", List.of(rejectedCheckpoint, acceptedCheckpoint));
        var successor = checkpoint("successor", resources("use_result", List.of(stack(RESULT, 20)),
            List.of(), List.of(stack(PRODUCT, 10))));
        var root = sequence("nested_root", outer, successor);
        var process = process(fixture.machine, 0, root);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var outerPath = outer.path(root.path(null));
        var rejectedInnerPath = inner.path(rejectedGroup.path(rejectedCheckpoint.path(outerPath)));
        var acceptedInnerPath = inner.path(acceptedGroup.path(acceptedCheckpoint.path(outerPath)));
        var rejectedLeafPath = observer.path(innerCycle.path(innerCheckpoint.path(rejectedInnerPath)));
        var acceptedLeafPath = observer.path(innerCycle.path(innerCheckpoint.path(acceptedInnerPath)));
        assertEquals(List.of(new Visit(rejectedLeafPath, RecipeStage.START), new Visit(rejectedLeafPath, RecipeStage.TICK),
            new Visit(rejectedLeafPath, RecipeStage.END), new Visit(acceptedLeafPath, RecipeStage.START),
            new Visit(acceptedLeafPath, RecipeStage.TICK), new Visit(acceptedLeafPath, RecipeStage.END)), visits);
        assertNotEquals(rejectedInnerPath, acceptedInnerPath);
        assertNull(SelectiveState.getOrCreate(process).selection(rejectedInnerPath));
        assertEquals(0, decision(process, acceptedInnerPath).index());
        assertTrue(decision(process, acceptedInnerPath).completed());
        assertEquals(1, decision(process, outerPath).index());
        assertEquals(0, fixture.storage.availableAmount(RESULT));
        fixture.assertContents(100, 100, 10);
    }

    @Test
    void plannedFallbackRetainsAnotherWorkSlotsStagedPrefixOnTheSamePhysicalSources() {
        var fixture = new Fixture();
        var observations = new SourceObservations();
        var prefixMachine = machine(fixture.storage, fixture.energy, observations);
        var choiceMachine = machine(fixture.storage, fixture.energy, observations);
        var prefixEffects = new PhaseEffects();
        var rejectedEffects = new PhaseEffects();
        var prefix = process(prefixMachine, 1, energy("prefix_energy", 20, 0, 0),
            resources("prefix_material", List.of(stack(MATERIAL, 10)), List.of(), List.of()),
            external("prefix_business", (_, phase) -> phase == RecipeStage.START
                ? prefixEffects.transaction() : RequirementTransaction.SUCCESS));
        var selection = selector("planned_choice", List.of(
            rejectingCandidate("rejected", fixture, rejectedEffects, RecipeStage.TICK, new ProcessResult.Blocked("candidate missing"), 80, 90),
            succeedingCandidate("chosen", external("planned_fallback_boundary", (_, phase) -> {
                if (phase == RecipeStage.START) {
                    fixture.assertContents(80, 90, 0);
                    assertEquals(1, prefixEffects.active.get(), "Another slot's staged business is outside the candidate savepoint");
                    assertEquals(0, rejectedEffects.active.get());
                    assertEquals(2, observations.rollbacks.get(), "Fallback waits for original source-lane compensation");
                }
                return RequirementTransaction.SUCCESS;
            }))));
        var chosen = process(choiceMachine, 1, selection);
        var prefixHost = new MachineWorkSlots(prefixMachine, List.of());
        var choiceHost = new MachineWorkSlots(choiceMachine, List.of());
        assertTrue(prefixHost.start(prefix));
        assertTrue(choiceHost.start(chosen));
        Thread owner = Thread.currentThread();
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(prefixHost, choiceHost), deadline(),
                new PlannedCancellationToken(), () -> true);
            assertTrue(coordinator.advance(deadline()));
            assertTrue(coordinator.finished());
            assertNull(coordinator.failure());
            assertNull(coordinator.stopReason());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, prefix.state().lifecycle());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, chosen.state().lifecycle());
            assertEquals(1, prefixEffects.commits.get());
            assertEquals(0, prefixEffects.rollbacks.get());
            assertEquals(0, rejectedEffects.active.get());
            fixture.assertContents(70, 70, 20);
            assertEquals(6, observations.applies.size());
            assertEquals(6, observations.exposures.get());
            assertEquals(4, observations.completions.get());
            assertEquals(2, observations.rollbacks.get());
            var lane = observations.applies.peek();
            assertNotNull(lane);
            assertNotSame(owner, lane);
            assertTrue(observations.applies.stream().allMatch(thread -> thread == lane));
            assertTrue(coordinator.advance(deadline()));
            assertEquals(6, observations.applies.size(), "Neither slot's source actions replay");
        }
    }

    @Test
    void plannedOuterFailureCompensatesRejectedAndSuccessfulCandidatesOnTheirOriginalSourceLane() {
        var fixture = new Fixture();
        var observations = new SourceObservations();
        var machine = machine(fixture.storage, fixture.energy, observations);
        var rejectedEffects = new PhaseEffects();
        var failure = new ProcessResult.Failure("planned outer failure");
        var selection = selector("planned_outer_failure", List.of(
            rejectingCandidate("rejected", fixture, rejectedEffects, RecipeStage.END, new ProcessResult.Blocked("missing"), 100, 100),
            succeedingCandidate("chosen", external("accepted_observer", (_, _) -> RequirementTransaction.SUCCESS))));
        var process = process(machine, 1, selection, external("planned_outer_gate", (_, phase) -> {
            if (phase != RecipeStage.START) {
                return RequirementTransaction.SUCCESS;
            }
            fixture.assertContents(90, 80, 20);
            return rejected(failure);
        }));
        var host = new MachineWorkSlots(machine, List.of());
        assertTrue(host.start(process));
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), new PlannedCancellationToken(), () -> true);
            assertTrue(coordinator.advance(deadline()));
            assertTrue(coordinator.finished());
            assertNull(coordinator.failure());
            assertSame(failure, process.state().result());
            assertEquals(RecipeProcess.Lifecycle.FAILED, process.state().lifecycle());
            fixture.assertContents(100, 100, 0);
            assertEquals(6, observations.applies.size());
            assertEquals(6, observations.rollbacks.get());
            assertEquals(0, observations.completions.get());
            assertNull(SelectiveState.getOrCreate(process).selection(selection.path(null)));
        }
    }

    @Test
    void cancellationKeepsTheOriginalPlannedCandidateCleanupPendingUntilItsUnknownRefundIsConfirmed() {
        var fixture = new Fixture();
        var delayed = new DelayedRollback();
        var observations = new SourceObservations(delayed);
        var machine = machine(fixture.storage, fixture.energy, observations);
        var rejectedEffects = new PhaseEffects();
        var fallbackStarts = new AtomicInteger();
        var selection = selector("pending_choice", List.of(
            rejectingCandidate("rejected", fixture, rejectedEffects, RecipeStage.TICK, new ProcessResult.Blocked("missing"), 100, 100),
            succeedingCandidate("unused", external("unused_fallback", (_, phase) -> {
                if (phase == RecipeStage.START) {
                    fallbackStarts.incrementAndGet();
                }
                return RequirementTransaction.SUCCESS;
            }))));
        var process = process(machine, 1, selection);
        var host = new MachineWorkSlots(machine, List.of());
        assertTrue(host.start(process));
        var cancellation = new PlannedCancellationToken();
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(), cancellation, () -> true);
            assertSame(delayed.failure, assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())));
            assertFalse(coordinator.finished());
            var pending = process.pendingTick();
            assertNotNull(pending);
            var stage = pending.currentStage();
            assertNotNull(stage);
            assertFalse(stage.settled());
            assertEquals(1, observations.rollbacks.get(), "UNKNOWN stops before refunding the dependent prefix token");
            assertEquals(0, fallbackStarts.get());
            assertEquals(0, rejectedEffects.active.get());
            assertThrows(IllegalStateException.class, () -> fixture.storage.availableAmount(MATERIAL));
            assertThrows(IllegalStateException.class, fixture.energy::storedEnergy);
            coordinator.cancel(PlannedCancellationToken.Reason.EXPLICIT_CANCEL);
            assertEquals(PlannedCancellationToken.Reason.EXPLICIT_CANCEL, cancellation.reason());
            assertFalse(coordinator.advance(deadline()));
            assertSame(pending, process.pendingTick());
            assertSame(stage, pending.currentStage());
            assertEquals(1, observations.rollbacks.get(), "Cancellation cannot replay an uncertain source callback");
            assertEquals(0, fallbackStarts.get());
            delayed.confirmed.set(true);
            assertTrue(coordinator.advance(deadline()));
            assertTrue(coordinator.finished());
            assertSame(delayed.failure, coordinator.failure());
            assertNull(process.pendingTick());
            fixture.assertContents(100, 100, 0);
            assertEquals(2, observations.applies.size());
            assertEquals(2, observations.rollbacks.get());
            assertEquals(0, observations.completions.get());
            assertEquals(0, fallbackStarts.get());
            assertNull(SelectiveState.getOrCreate(process).selection(selection.path(null)));
        }
    }

    @Test
    void cancellingAnInflightLocalRefundWaitsForActualLaneExitThenRefundsAnotherSlotsPrefixWithoutFallback() {
        var fixture = new Fixture();
        var blocking = new BlockingRollback();
        var observations = new SourceObservations(blocking);
        var prefixMachine = machine(fixture.storage, fixture.energy, observations);
        var choiceMachine = machine(fixture.storage, fixture.energy, observations);
        var prefixEffects = new PhaseEffects();
        var rejectedEffects = new PhaseEffects();
        var fallbackStarts = new AtomicInteger();
        var prefix = process(prefixMachine, 1, energy("cancel_prefix_energy", 20, 0, 0),
            resources("cancel_prefix_material", List.of(stack(MATERIAL, 10)), List.of(), List.of()),
            external("cancel_prefix_business", (_, phase) -> phase == RecipeStage.START
                ? prefixEffects.transaction() : RequirementTransaction.SUCCESS));
        var selection = selector("inflight_choice", List.of(
            rejectingCandidate("rejected", fixture, rejectedEffects, RecipeStage.TICK, new ProcessResult.Blocked("missing"), 80, 90),
            succeedingCandidate("unused", external("inflight_unused_fallback", (_, phase) -> {
                if (phase == RecipeStage.START) {
                    fallbackStarts.incrementAndGet();
                }
                return RequirementTransaction.SUCCESS;
            }))));
        var chosen = process(choiceMachine, 1, selection);
        var prefixHost = new MachineWorkSlots(prefixMachine, List.of());
        var choiceHost = new MachineWorkSlots(choiceMachine, List.of());
        assertTrue(prefixHost.start(prefix));
        assertTrue(choiceHost.start(chosen));
        var cancellation = new PlannedCancellationToken();
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(prefixHost, choiceHost), deadline(), cancellation, () -> true);
            try {
                assertFalse(coordinator.advance(PlannedDeadline.after(Duration.ofSeconds(1))));
                blocking.awaitEntered();
                assertTrue(coordinator.hasActiveSources(), "A timeout does not prove that the original source action exited");
                assertFalse(coordinator.finished());
                assertNull(coordinator.failure());
                var pending = chosen.pendingTick();
                assertNotNull(pending);
                var stage = pending.currentStage();
                assertNotNull(stage);
                assertFalse(stage.settled());
                assertEquals(1, observations.rollbacks.get());
                assertEquals(1, prefixEffects.active.get());
                assertEquals(0, rejectedEffects.active.get());
                assertEquals(0, fallbackStarts.get());
                assertThrows(IllegalStateException.class, () -> fixture.storage.availableAmount(MATERIAL));
                assertThrows(IllegalStateException.class, fixture.energy::storedEnergy);
                coordinator.cancel(PlannedCancellationToken.Reason.EXPLICIT_CANCEL);
                assertFalse(coordinator.advance(deadline()));
                assertSame(pending, chosen.pendingTick());
                assertSame(stage, pending.currentStage());
                assertTrue(coordinator.hasActiveSources());
                assertEquals(1, observations.rollbacks.get(), "The original lane callback remains the cleanup owner");
                assertEquals(0, fallbackStarts.get());
                blocking.release.countDown();
                coordinator.awaitExit();
                assertTrue(coordinator.advance(deadline()));
                assertTrue(coordinator.finished());
                assertNull(coordinator.failure());
                assertEquals(PlannedCancellationToken.Reason.EXPLICIT_CANCEL, cancellation.reason());
                fixture.assertContents(100, 100, 0);
                assertEquals(3, observations.applies.size());
                assertEquals(3, observations.rollbacks.get());
                assertEquals(0, observations.completions.get());
                assertEquals(1, prefixEffects.commits.get());
                assertEquals(1, prefixEffects.rollbacks.get());
                assertEquals(0, prefixEffects.active.get());
                assertEquals(2, rejectedEffects.rollbacks.get());
                assertEquals(0, fallbackStarts.get());
                assertEquals(RecipeProcess.Lifecycle.NEW, prefix.state().lifecycle());
                assertEquals(RecipeProcess.Lifecycle.NEW, chosen.state().lifecycle());
                assertNull(chosen.pendingTick());
            } finally {
                blocking.release.countDown();
            }
        }
    }

    private static final class Sequence implements RequirementTransaction, RequirementContinuation {
        private final RecipeProcess process;
        private final List<RecipeRequirement<?>> children;
        private final RecipeStage phase;
        private int cursor;
        private Resolution terminal = Resolution.REVERSIBLE;

        private Sequence(RecipeProcess process, List<RecipeRequirement<?>> children, RecipeStage phase) {
            this.process = process;
            this.children = children;
            this.phase = phase;
        }

        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public RequirementContinuation continuation() {
            return this;
        }

        @Override
        public void commit() {
        }

        @Override
        public void complete() {
            terminal = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            terminal = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return terminal;
        }

        @Override
        public @Nullable RequirementTransaction acquireNext() {
            if (terminal != Resolution.REVERSIBLE) {
                throw new IllegalStateException("Sequence already resolved");
            }
            return cursor == children.size() ? null : children.get(cursor++).acquire(process, phase);
        }
    }

    private static final class PhaseEffects {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        private RecipeRequirement<String> requirement(String name) {
            return external(name, (_, _) -> transaction());
        }

        private RequirementTransaction transaction() {
            return new RequirementTransaction() {
                private boolean applied;
                private Resolution terminal = Resolution.REVERSIBLE;

                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    assertFalse(applied);
                    applied = true;
                    active.incrementAndGet();
                    commits.incrementAndGet();
                }

                @Override
                public void complete() {
                    terminal = Resolution.COMPLETED;
                }

                @Override
                public void rollback() {
                    assertEquals(Resolution.REVERSIBLE, terminal);
                    if (applied) {
                        active.decrementAndGet();
                    }
                    rollbacks.incrementAndGet();
                    terminal = Resolution.ROLLED_BACK;
                }

                @Override
                public Resolution resolution() {
                    return terminal;
                }
            };
        }
    }

    private record Visit(RequirementPath path, RecipeStage phase) {
    }

    private static final class Fixture {
        private final SlottedResourceBuffer storage;
        private final EnergyBuffer energy = new EnergyBuffer(100, () -> {
        });
        private final MachineInstance machine;

        private Fixture() {
            var capacities = new Reference2LongArrayMap<AEKeyType>();
            capacities.put(TYPE, 100L);
            storage = new SlottedResourceBuffer(3, capacities, () -> {
            });
            storage.insert(MATERIAL, 100, Actionable.MODULATE);
            energy.receiveEnergy(100, Actionable.MODULATE);
            machine = machine(storage, energy, null);
        }

        private void assertContents(long energyAmount, long materialAmount, long productAmount) {
            assertEquals(energyAmount, energy.savedEnergy());
            assertEquals(energyAmount, energy.energySnapshot().availableEnergy());
            assertEquals(materialAmount, storage.availableAmount(MATERIAL));
            assertEquals(productAmount, storage.availableAmount(PRODUCT));
        }
    }

    private record LoopHatch(SlottedResourceBuffer storage) implements ResourceHatch {
        @Override
        public SlottedResourceBuffer input() {
            return storage;
        }

        @Override
        public SlottedResourceBuffer output() {
            return storage;
        }

        @Override
        public boolean isValid() {
            return storage.isValid();
        }
    }

    private static final class SourceObservations {
        private final ConcurrentLinkedQueue<Thread> applies = new ConcurrentLinkedQueue<>();
        private final AtomicInteger exposures = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final @Nullable DelayedRollback delayed;
        private final @Nullable BlockingRollback blocking;

        private SourceObservations() {
            this(null, null);
        }

        private SourceObservations(DelayedRollback delayed) {
            this(delayed, null);
        }

        private SourceObservations(BlockingRollback blocking) {
            this(null, blocking);
        }

        private SourceObservations(@Nullable DelayedRollback delayed, @Nullable BlockingRollback blocking) {
            this.delayed = delayed;
            this.blocking = blocking;
        }
    }

    private static final class DelayedRollback {
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final AtomicBoolean confirmed = new AtomicBoolean();
        private final IllegalStateException failure = new IllegalStateException("Refund applied but confirmation unavailable");
    }

    private static final class BlockingRollback {
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void awaitEntered() {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "The candidate must enter its original refund callback");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }

        private void blockUntilReleased() {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "Test must release the original source callback");
                    done = true;
                } catch (InterruptedException ignored) { /* Deadline interruption does not prove physical exit. */ }
            }
        }
    }

    private record ObservedEnergy(EnergyBuffer delegate, SourceObservations observations) implements EnergyHatch {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public EnergySnapshot energySnapshot() {
            return delegate.energySnapshot();
        }

        @Override
        public long storedEnergy() {
            return delegate.storedEnergy();
        }

        @Override
        public long capacity() {
            return delegate.capacity();
        }

        @Override
        public long extractEnergy(long amount, Actionable mode) {
            return delegate.extractEnergy(amount, mode);
        }

        @Override
        public long receiveEnergy(long amount, Actionable mode) {
            return delegate.receiveEnergy(amount, mode);
        }

        @Override
        public boolean isValid() {
            return delegate.isValid();
        }

        @Override
        public ReservationResult reserveExtractEnergy(EnergySnapshot snapshot, long amount) {
            return observed(delegate.reserveExtractEnergy(snapshot, amount));
        }

        @Override
        public ReservationResult reserveReceiveEnergy(EnergySnapshot snapshot, long amount) {
            return observed(delegate.reserveReceiveEnergy(snapshot, amount));
        }

        private ReservationResult observed(ReservationResult reserved) {
            if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                return reserved;
            }
            var token = Objects.requireNonNull(reserved.reservation());
            return new ReservationResult(reserved.status(), new ResourceReservation() {
                private boolean awaitingConfirmation;

                @Override
                public StorageContext context() {
                    return token.context();
                }

                @Override
                public boolean canApply() {
                    return token.canApply();
                }

                @Override
                public void apply() {
                    observations.applies.add(Thread.currentThread());
                    token.apply();
                }

                @Override
                public boolean supportsSequentialUse() {
                    return token.supportsSequentialUse();
                }

                @Override
                public void exposeApplied() {
                    observations.exposures.incrementAndGet();
                    token.exposeApplied();
                }

                @Override
                public void complete() {
                    assertOriginalLane();
                    observations.completions.incrementAndGet();
                    token.complete();
                }

                @Override
                public void rollback() {
                    assertOriginalLane();
                    observations.rollbacks.incrementAndGet();
                    var blocking = observations.blocking;
                    if (blocking != null && blocking.armed.compareAndSet(true, false)) {
                        blocking.blockUntilReleased();
                    }
                    token.rollback();
                    var delayed = observations.delayed;
                    if (delayed != null && delayed.armed.compareAndSet(true, false)) {
                        awaitingConfirmation = true;
                        throw delayed.failure;
                    }
                }

                @Override
                public Resolution resolution() {
                    var delayed = observations.delayed;
                    return awaitingConfirmation && delayed != null && !delayed.confirmed.get() ? Resolution.UNKNOWN : token.resolution();
                }

                private void assertOriginalLane() {
                    assertSame(observations.applies.peek(), Thread.currentThread());
                }
            });
        }
    }

    private static final class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        private final ExecutorService lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});

        @Override
        public void close() {
            workers.shutdownNow();
            lane.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(lane.awaitTermination(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }
}
