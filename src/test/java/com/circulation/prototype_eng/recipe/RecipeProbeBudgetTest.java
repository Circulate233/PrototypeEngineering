package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
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
import com.circulation.prototype_eng.api.recipe.scanning.FixedIoScanConstraint;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanPreparation;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanProbes;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.recipe.requirement.ResourceRequirementSystem;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeProbeBudgetTest {
    private static final KeyType TYPE = new KeyType("probe_budget");
    private static final Key INPUT = new Key(TYPE, "input");
    private static final Key OUTPUT = new Key(TYPE, "output");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("probe_budget_test", path);
    }

    private static RecipeProcess process(MachineInstance machine, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(machine, new MachineRecipe(id("recipe"), 2, List.of(requirements)), 17);
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        return new SlottedResourceBuffer(3, capacities, () -> {
        });
    }

    /**
     * Runs real kernel/source methods directly; it is not a mock scheduler or parallelism proof.
     */
    private static ProcessResult complete(RecipeScanProbes.Session session) {
        for (int call = 0; call < 10000; call++) {
            var work = new RecipeWorkBudget(1);
            for (var source : session.sources()) {
                if (session.resolvingSources())
                    session.finishSourceResolution(source, source.resolveWithinBudget(work));
                else if (source.state() == ResourceBatch.SourceState.RESERVED) source.applyWithinBudget(work);
            }
            var computation = session.computation();
            if (computation != null && computation.advance(work)) session.finishComputation(computation);
            if (session.advance(work, () -> true)) return session.result();
        }
        throw new AssertionError("Probe did not complete its real acquisition/rollback cursors");
    }

    private static RequirementTransaction projection(String name, List<String> events, AtomicInteger local) {
        events.add("prepare_" + name);
        local.incrementAndGet();
        return new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return ProcessResult.SUCCESS;
            }

            @Override
            public void commit() {
                throw new AssertionError("Preparation cannot commit");
            }

            @Override
            public void complete() {
                throw new AssertionError("Preparation cannot complete");
            }

            @Override
            public void rollback() {
                events.add("rollback_" + name);
                local.decrementAndGet();
            }
        };
    }

    private static RequirementTransaction flatReservationFailure(SlottedResourceBuffer input, AtomicInteger listed,
                                                                 AtomicInteger rolledBack, AtomicInteger results) {
        var tokens = new ObjectArrayList<ResourceReservation>();
        for (int token = 0; token < 20; token++) {
            var reservation = input.reserveExtract(input.snapshot(), INPUT, 1).reservation();
            assertNotNull(reservation);
            tokens.add(reservation);
        }
        var inventory = new AbstractList<ResourceReservation>() {
            @Override
            public ResourceReservation get(int index) {
                listed.incrementAndGet();
                return tokens.get(index);
            }

            @Override
            public int size() {
                return tokens.size();
            }
        };
        return new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                if (results != null) results.incrementAndGet();
                return new ProcessResult.Blocked("Flat child rejected after acquiring real holds");
            }

            @Override
            public List<ResourceReservation> reservations() {
                return inventory;
            }

            @Override
            public void commit() {
                throw new AssertionError("A failed flat child must not stage");
            }

            @Override
            public void complete() {
                throw new AssertionError("A failed flat child must not complete");
            }

            @Override
            public void rollback() {
                rolledBack.incrementAndGet();
            }
        };
    }

    @Test
    void aRealNestedResourceBindingWithdrawsItsOwnPreparedFactsAndPreservesOtherPathAndStageAliases() {
        var input = buffer();
        var output = buffer();
        input.insert(INPUT, 20, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(), List.of()));
        var io = RecipeRequirements.RESOURCES.requirement(id("fact_child"), new ResourceRequirement(List.of(new GenericStack(INPUT, 2)), List.of()));
        var selector = RecipeRequirements.SELECTIVE.requirement(id("fact_parent"), new SelectiveRequirement(List.of(io)));
        var process = process(machine, selector);
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        stage.acquirePreparedWithinBudget(new RecipeWorkBudget(10000));
        for (int step = 0; step < 100 && stage.computation() == null; step++)
            stage.resumePreparedWithinBudget(new RecipeWorkBudget(10000));
        var facts = Objects.requireNonNull(stage.computation());
        assertTrue(facts.advance(new RecipeWorkBudget(10000)));
        stage.retainPreparedFacts(facts);
        var parentPath = selector.path(null);
        var childPath = io.path(parentPath);
        process.withPreparationContext(childPath, () -> {
            assertSame(facts, process.pureFacts(RecipeStage.START), "The original nested binding publishes at its child path");
            process.retainPureFacts(RecipeStage.TICK, facts);
            return null;
        });
        process.withPreparationContext(parentPath, () -> {
            process.retainPureFacts(RecipeStage.START, facts);
            return null;
        });
        stage.withdrawPreparedFacts(facts);
        process.withPreparationContext(childPath, () -> {
            assertNull(process.pureFacts(RecipeStage.START));
            assertSame(facts, process.pureFacts(RecipeStage.TICK));
            return null;
        });
        process.withPreparationContext(parentPath, () -> {
            assertSame(facts, process.pureFacts(RecipeStage.START));
            return null;
        });
        RecipePureComputation replacement = RecipeWorkBudget::take;
        process.withPreparationContext(childPath, () -> {
            process.retainPureFacts(RecipeStage.START, replacement);
            return null;
        });
        stage.withdrawPreparedFacts(facts);
        process.withPreparationContext(childPath, () -> {
            assertSame(replacement, process.pureFacts(RecipeStage.START));
            return null;
        });
        for (int step = 0; step < 100 && stage.preparationPending(); step++)
            stage.resumePreparedWithinBudget(new RecipeWorkBudget(10000));
        assertFalse(stage.preparationPending());
        assertSame(ProcessResult.SUCCESS, stage.settleSynchronously());
        session.finish();
        assertEquals(18, input.amount(INPUT));
        assertEquals(18, input.availableAmount(INPUT));
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        process.requirePersistenceBoundary();
    }

    @Test
    void aPreparedPartialNativeHoldKeepsLegacySynchronousCompensationUnderTheOriginalLocks() {
        var input = buffer();
        input.insert(INPUT, 20, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, input)), List.of(), List.of()));
        var io = RecipeRequirements.RESOURCES.requirement(id("partial_sync_io"), new ResourceRequirement(List.of(new GenericStack(INPUT, 2)), List.of()));
        var process = process(machine, io);
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        stage.acquirePreparedWithinBudget(new RecipeWorkBudget(1));
        for (int step = 0; step < 1000 && input.availableAmount(INPUT) == 20; step++) {
            var work = new RecipeWorkBudget(1);
            var computation = stage.computation();
            if (computation != null && computation.advance(work)) stage.retainPreparedFacts(computation);
            stage.resumePreparedWithinBudget(work);
        }
        assertEquals(20, input.amount(INPUT));
        assertEquals(18, input.availableAmount(INPUT));
        assertTrue(stage.preparationPending(), "The original native capture has a hold but has not transferred its final token");
        assertTrue(stage.finishCapturedInventoryWithinBudget(new RecipeWorkBudget(10000)));
        stage.rollbackSynchronously();
        session.finish();
        assertEquals(20, input.amount(INPUT));
        assertEquals(20, input.availableAmount(INPUT));
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        process.requirePersistenceBoundary();
    }

    @Test
    @SuppressWarnings("ReturnOfInnerClass")
        // Fixture callbacks intentionally retain the original transaction and preparation state.
    void oneUnitSlicesRetainPreparationAndAcquireEachStartOnceThenRollbackInReverseOrder() {
        var machine = new MachineInstance();
        var events = new ObjectArrayList<String>();
        var local = new AtomicInteger();
        RecipeScanPreparation first = _ -> projection("first", events, local);
        RecipeScanPreparation second = _ -> projection("second", events, local);
        var type = new RecipeRequirementType<Boolean>(id("ordered"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                assertTrue(process.probing());
                assertEquals(2, local.get());
                events.add("start_" + requirement.id().getPath());
                return new RequirementTransaction() {
                    @Override
                    public ProcessResult result() {
                        return ProcessResult.SUCCESS;
                    }

                    @Override
                    public void commit() {
                        throw new AssertionError("Flat probe must not commit");
                    }

                    @Override
                    public void complete() {
                        throw new AssertionError("Probe must not complete");
                    }

                    @Override
                    public void rollback() {
                        events.add("undo_" + requirement.id().getPath());
                    }
                };
            }
        });
        var process = process(machine, type.requirement(id("one"), true), type.requirement(id("two"), true));
        var session = RecipeScanProbes.begin(process, List.of(first, second), List.of());
        assertFalse(session.advance(new RecipeWorkBudget(0), () -> true));
        assertTrue(events.isEmpty());
        assertFalse(session.advance(new RecipeWorkBudget(1), () -> true));
        assertEquals(List.of("prepare_first"), events);
        assertSame(ProcessResult.SUCCESS, complete(session));
        assertEquals(List.of("prepare_first", "prepare_second", "start_one", "start_two", "undo_two", "undo_one", "rollback_second", "rollback_first"), events);
        assertEquals(0, local.get());
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        process.requirePersistenceBoundary();
    }

    @Test
    void cancellationDuringLocalPreparationUndoDoesNotEnterAnotherPreparationOrStart() {
        var local = new AtomicInteger();
        var events = new ObjectArrayList<String>();
        var starts = new AtomicInteger();
        var type = new RecipeRequirementType<Boolean>(id("cancelled"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                starts.incrementAndGet();
                return RequirementTransaction.SUCCESS;
            }
        });
        var process = process(new MachineInstance(), type.requirement(id("start"), true));
        var session = RecipeScanProbes.begin(process, List.of(_ -> projection("first", events, local), _ -> projection("second", events, local)), List.of());
        assertFalse(session.advance(new RecipeWorkBudget(1), () -> true));
        session.cancel();
        assertTrue(session.advance(new RecipeWorkBudget(1), () -> false));
        assertEquals(List.of("prepare_first", "rollback_first"), events);
        assertEquals(0, local.get());
        assertEquals(0, starts.get());
        process.requirePersistenceBoundary();
    }

    @Test
    void anUnknownPreparationRollbackKeepsItsExactHandleAndNeverReplaysTheCallback() {
        var rollbackCalls = new AtomicInteger();
        var proof = new AtomicReference<>(ReversibleTransaction.Resolution.UNKNOWN);
        var process = process(new MachineInstance());
        var session = RecipeScanProbes.begin(process, List.of(_ -> new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return ProcessResult.SUCCESS;
            }

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
                rollbackCalls.incrementAndGet();
                throw new IllegalStateException("No terminal proof yet");
            }

            @Override
            public Resolution resolution() {
                return proof.get();
            }
        }), List.of());
        assertFalse(session.advance(new RecipeWorkBudget(1), () -> true));
        session.cancel();
        assertThrows(IllegalStateException.class, () -> session.advance(new RecipeWorkBudget(1), () -> false));
        assertFalse(session.finished());
        assertFalse(session.advance(new RecipeWorkBudget(1), () -> false));
        assertEquals(1, rollbackCalls.get());
        proof.set(ReversibleTransaction.Resolution.ROLLED_BACK);
        assertTrue(session.advance(new RecipeWorkBudget(1), () -> false));
        assertEquals(1, rollbackCalls.get());
        assertFalse(process.persistenceReady());
    }

    @Test
    void fixedTickEndAndStartUseOneAllowanceAndRestoreEveryRealResourceBalance() {
        var input = buffer();
        var output = buffer();
        input.insert(INPUT, 20, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(), List.of()));
        var io = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(
            List.of(new GenericStack(INPUT, 2)), List.of(new GenericStack(INPUT, 3)), List.of(new GenericStack(OUTPUT, 4)), List.of(new GenericStack(OUTPUT, 5)), false));
        var process = process(machine, io);
        var session = RecipeScanProbes.begin(process, List.of(), List.of(new FixedIoScanConstraint()));
        assertFalse(session.advance(new RecipeWorkBudget(1), () -> true));
        assertSame(ProcessResult.SUCCESS, complete(session));
        assertEquals(20, input.amount(INPUT));
        assertEquals(20, input.availableAmount(INPUT));
        assertEquals(0, output.amount(OUTPUT));
        assertEquals(0, process.state().successfulTicks());
        process.requirePersistenceBoundary();
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(new RecipeProcess(machine, process.recipe(), 17)));
    }

    @Test
    @SuppressWarnings("ReturnOfInnerClass")
        // Fixture callbacks intentionally retain the original transaction and preparation state.
    void aNestedSelectorWaitsForTheRejectedCheckpointRollbackBeforeItsNextCandidateAndFinallyRestoresStorage() {
        var input = buffer();
        var output = buffer();
        input.insert(INPUT, 10, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(), List.of()));
        var effects = new AtomicInteger();
        var starts = new AtomicInteger();
        var type = new RecipeRequirementType<Boolean>(id("rejected"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                starts.incrementAndGet();
                return new RequirementTransaction() {
                    private boolean committed;

                    @Override
                    public ProcessResult result() {
                        return ProcessResult.SUCCESS;
                    }

                    @Override
                    public void commit() {
                        committed = true;
                        effects.incrementAndGet();
                    }

                    @Override
                    public void complete() {
                        throw new AssertionError("Probe cannot complete checkpoint effects");
                    }

                    @Override
                    public void rollback() {
                        if (committed) effects.decrementAndGet();
                    }
                };
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                assertEquals(1, effects.get());
                return new RequirementTransaction() {
                    @Override
                    public ProcessResult result() {
                        return new ProcessResult.Blocked("first candidate rejected");
                    }

                    @Override
                    public void commit() {
                    }

                    @Override
                    public void rollback() {
                    }
                };
            }
        });
        var rejected = RecipeRequirements.CHECKPOINT.requirement(id("first_checkpoint"), new CheckpointRequirement(0, type.requirement(id("first_child"), true)));
        var resourceSystem = new ResourceRequirementSystem();
        var acceptedType = new RecipeRequirementType<ResourceRequirement>(id("accepted"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement, RecipeStage stage) {
                assertEquals(0, effects.get(), "Rejected candidate must finish reverse cleanup before the next capture");
                return resourceSystem.prepare(process, requirement, stage);
            }
        });
        var resource = acceptedType.requirement(id("accepted_io"), new ResourceRequirement(List.of(new GenericStack(INPUT, 2)), List.of(new GenericStack(OUTPUT, 2))));
        var accepted = RecipeRequirements.CHECKPOINT.requirement(id("second_checkpoint"), new CheckpointRequirement(0, resource));
        var selected = RecipeRequirements.SELECTIVE.requirement(id("choice"), new SelectiveRequirement(List.of(rejected, accepted)));
        var process = process(machine, selected);
        var session = RecipeScanProbes.begin(process, List.of(), List.of());
        assertSame(ProcessResult.SUCCESS, complete(session));
        assertEquals(1, starts.get());
        assertEquals(0, effects.get());
        assertEquals(10, input.amount(INPUT));
        assertEquals(10, input.availableAmount(INPUT));
        assertEquals(0, output.amount(OUTPUT));
        process.requirePersistenceBoundary();
    }

    @Test
    void aFlatRejectedChildInsideACheckpointRestoresItsRealHoldsBeforeTryingTheNextChild() {
        var input = buffer();
        input.insert(INPUT, 20, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, input)), List.of(), List.of()));
        var listed = new AtomicInteger();
        var rejected = new AtomicInteger();
        var accepted = new AtomicInteger();
        var type = new RecipeRequirementType<Boolean>(id("flat_child"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                if (requirement.payload()) {
                    assertEquals(20, input.availableAmount(INPUT), "Flat rejection must undo its holds before the next capture");
                    accepted.incrementAndGet();
                    return RequirementTransaction.SUCCESS;
                }
                return flatReservationFailure(input, listed, rejected, null);
            }
        });
        var choice = RecipeRequirements.SELECTIVE.requirement(id("flat_choice"), new SelectiveRequirement(List.of(
            type.requirement(id("flat_rejected"), false), type.requirement(id("flat_accepted"), true))));
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("flat_checkpoint"), new CheckpointRequirement(0, choice));
        var process = process(machine, checkpoint);
        var session = RecipeScanProbes.begin(process, List.of(), List.of());
        for (int call = 0; !session.finished() && call < 10000; call++) {
            int before = listed.get();
            var work = new RecipeWorkBudget(1);
            for (var source : session.sources()) {
                if (session.resolvingSources())
                    session.finishSourceResolution(source, source.resolveWithinBudget(work));
                else if (source.state() == ResourceBatch.SourceState.RESERVED) source.applyWithinBudget(work);
            }
            var computation = session.computation();
            if (computation != null && computation.advance(work)) session.finishComputation(computation);
            session.advance(work, () -> true);
            assertTrue(listed.get() - before <= 1, "One owner slice may enumerate only one actual child token");
        }
        assertTrue(session.finished());
        assertSame(ProcessResult.SUCCESS, session.result());
        assertEquals(20, listed.get());
        assertEquals(1, rejected.get());
        assertEquals(1, accepted.get());
        assertEquals(20, input.amount(INPUT));
        assertEquals(20, input.availableAmount(INPUT));
        process.requirePersistenceBoundary();
    }

    @Test
    void cancellationHalfwayThroughAFlatChildInventoryNeverReadsItsResultOrAcquiresTheNextChild() {
        var input = buffer();
        input.insert(INPUT, 20, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, input)), List.of(), List.of()));
        var listed = new AtomicInteger();
        var rolledBack = new AtomicInteger();
        var results = new AtomicInteger();
        var next = new AtomicInteger();
        var type = new RecipeRequirementType<Boolean>(id("cancel_flat_child"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                if (requirement.payload()) {
                    next.incrementAndGet();
                    return RequirementTransaction.SUCCESS;
                }
                return flatReservationFailure(input, listed, rolledBack, results);
            }
        });
        var choice = RecipeRequirements.SELECTIVE.requirement(id("cancel_flat_choice"), new SelectiveRequirement(List.of(
            type.requirement(id("cancel_flat_rejected"), false), type.requirement(id("cancel_flat_next"), true))));
        var process = process(machine, choice);
        var session = RecipeScanProbes.begin(process, List.of(), List.of());
        for (int call = 0; listed.get() < 5 && call < 1000; call++)
            session.advance(new RecipeWorkBudget(1), () -> true);
        assertEquals(5, listed.get());
        session.cancel();
        complete(session);
        assertEquals(20, listed.get());
        assertEquals(0, results.get());
        assertEquals(0, next.get());
        assertEquals(1, rolledBack.get());
        assertEquals(20, input.amount(INPUT));
        assertEquals(20, input.availableAmount(INPUT));
        process.requirePersistenceBoundary();
    }

    @Test
    @SuppressWarnings("ReturnOfInnerClass")
        // Fixture callbacks intentionally retain the original transaction and preparation state.
    void aFlatChildKeepsTwoPreparedPausesInOneRejectedSuffixWhileTheCheckpointPrefixRemainsApplied() {
        var input = buffer();
        input.insert(INPUT, 20, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, input)), List.of(), List.of()));
        var pauses = new AtomicInteger();
        var rejected = new AtomicInteger();
        var next = new AtomicInteger();
        var type = new RecipeRequirementType<Boolean>(id("history_child"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<Boolean> requirement, RecipeStage stage) {
                if (stage != RecipeStage.START) return null;
                return new RequirementPreparation() {
                    private final ObjectArrayList<ResourceReservation> cumulative = new ObjectArrayList<>();
                    private int cursor;

                    @Override
                    public boolean ownerWorkMetered() {
                        return true;
                    }

                    @Override
                    public RecipePureComputation computation() {
                        return null;
                    }

                    @Override
                    public RequirementTransaction acquire() {
                        if (requirement.payload()) {
                            assertEquals(18, input.amount(INPUT), "The outer successful prefix stays applied during child cleanup");
                            assertEquals(18, input.availableAmount(INPUT), "Both prepared child holds must be gone before the next candidate");
                            next.incrementAndGet();
                            return RequirementTransaction.SUCCESS;
                        }
                        if (cursor < 2) {
                            long amount = cursor++ == 0 ? 3 : 4;
                            var token = input.reserveExtract(input.snapshotFor(INPUT), INPUT, amount).reservation();
                            assertNotNull(token);
                            cumulative.add(token);
                            pauses.incrementAndGet();
                            return new PreparedRequirement(this) {
                                @Override
                                public List<ResourceReservation> reservations() {
                                    return cumulative;
                                }
                            };
                        }
                        return new RequirementTransaction() {
                            @Override
                            public ProcessResult result() {
                                return new ProcessResult.Blocked("Failed after two original preparation pauses");
                            }

                            @Override
                            public List<ResourceReservation> reservations() {
                                return cumulative;
                            }

                            @Override
                            public void commit() {
                                throw new AssertionError("Failed child must not stage");
                            }

                            @Override
                            public void rollback() {
                                rejected.incrementAndGet();
                            }
                        };
                    }
                };
            }
        });
        var prefixIo = RecipeRequirements.RESOURCES.requirement(id("history_prefix_io"), new ResourceRequirement(List.of(new GenericStack(INPUT, 2)), List.of()));
        // Root requirements execute by type; same-type checkpoints retain declaration order.
        var prefix = RecipeRequirements.CHECKPOINT.requirement(id("history_prefix"), new CheckpointRequirement(0, prefixIo));
        var choice = RecipeRequirements.SELECTIVE.requirement(id("history_choice"), new SelectiveRequirement(List.of(
            type.requirement(id("history_failed"), false), type.requirement(id("history_next"), true))));
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("history_checkpoint"), new CheckpointRequirement(0, choice));
        var process = process(machine, prefix, checkpoint);
        assertEquals(List.of(prefix, checkpoint), process.recipe().orderedRequirements());
        var session = RecipeScanProbes.begin(process, List.of(), List.of());
        assertSame(ProcessResult.SUCCESS, complete(session));
        assertEquals(2, pauses.get());
        assertEquals(1, rejected.get());
        assertEquals(1, next.get());
        assertEquals(20, input.amount(INPUT));
        assertEquals(20, input.availableAmount(INPUT));
        process.requirePersistenceBoundary();
    }

    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override
        public boolean isValid() {
            return input.context().isValid() && output.context().isValid();
        }
    }
}
