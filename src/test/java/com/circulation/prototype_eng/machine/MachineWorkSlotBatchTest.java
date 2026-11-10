package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineWorkSlotBatchTest {
    private static final Runnable NO_OP = () -> {
    };

    private static void settleAllSynchronously(MachineWorkSlots.TickBatch batch) {
        List<RecipeProcess.Stage> stages;
        while (!(stages = batch.nextRound()).isEmpty()) {
            for (var stage : stages) {
                stage.acquire();
                stage.settleSynchronously();
            }
        }
    }

    private static ResourceReservation delayedSource(ResourceReservation token, boolean[] recoverable, int[] attempts,
                                                     RuntimeException primary) {
        return new ResourceReservation() {
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
                token.apply();
            }

            @Override
            public void complete() {
                attempts[0]++;
                if (!recoverable[0]) {
                    throw primary;
                }
                token.complete();
            }

            @Override
            public void rollback() {
                token.rollback();
            }

            @Override
            public Resolution resolution() {
                return recoverable[0] ? token.resolution() : Resolution.UNKNOWN;
            }
        };
    }

    private static MachineInstance machine(int capacity) {
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, capacity);
        return machine;
    }

    private static MachineWorkSlots host(MachineInstance owner, String... names) {
        var definitions = new ObjectArrayList<WorkSlotDefinition>();
        for (var name : names) {
            definitions.add(new WorkSlotDefinition(name));
        }
        return new MachineWorkSlots(owner, definitions);
    }

    private static RecipeProcess process(MachineInstance owner, String name, int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(owner, new MachineRecipe(id(name), duration, List.of(requirements)), 1);
    }

    private static RequirementTransaction outcome(ProcessResult result) {
        return new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return result;
            }

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
            }
        };
    }

    private static RecipeRequirement<String> requirement(BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.START);
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.TICK);
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(id("observer"), system).requirement(id("observer"), "value");
    }

    private static void components(RecipeProcess process, String name, List<String> events, Runnable pre, Runnable post) {
        process.components().create(new ComponentType<>(id("component"), _ -> "value", List.of(new ComponentSystem<RecipeProcess, String>() {
            @Override
            public ResourceLocation id() {
                return MachineWorkSlotBatchTest.id("system");
            }

            @Override
            public void onPreTick(RecipeProcess owner, String component) {
                events.add("pre:" + name);
                pre.run();
            }

            @Override
            public void onTick(RecipeProcess owner, String component) {
                events.add("tick:" + name);
            }

            @Override
            public void onPostTick(RecipeProcess owner, String component) {
                events.add("post:" + name);
                post.run();
            }
        })), process);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("work_slot_batch_test", name);
    }

    @Test
    void sharedSourceRoundsKeepBlockedWorkAndPublishFailureAndCompletionAfterAllPosts() throws Exception {
        var machine = machine(4);
        var energy = new EnergyBuffer(100, NO_OP);
        energy.receiveEnergy(100, Actionable.MODULATE);
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy), List.of(energy)));
        var host = host(machine, "blocked", "failed", "left", "right");
        var events = new ObjectArrayList<String>();
        var blockedResult = new ProcessResult.Blocked("waiting");
        var failureResult = new ProcessResult.Failure("rejected");
        var blocked = process(machine, "blocked", 1, requirement((_, _) -> outcome(blockedResult)));
        var failed = process(machine, "failed", 1, requirement((_, _) -> outcome(failureResult)));
        var io = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 1, false));
        var left = process(machine, "left", 1, io);
        var right = process(machine, "right", 1, io);
        for (var process : List.of(blocked, failed, left, right)) {
            assertTrue(host.start(process));
            components(process, process.recipe().id().getPath(), events, NO_OP, NO_OP);
        }
        var batch = host.prepareTickBatch();
        batch.begin();
        var roundSizes = new ObjectArrayList<Integer>();
        @SuppressWarnings("resource")
        var lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            List<RecipeProcess.Stage> round;
            while (!(round = batch.nextRound()).isEmpty()) {
                roundSizes.add(round.size());
                var accepted = new ObjectArrayList<RecipeProcess.Stage>();
                for (var stage : round) {
                    stage.acquire();
                    if (stage.result() == ProcessResult.SUCCESS) {
                        accepted.add(stage);
                    } else {
                        stage.rollbackSynchronously();
                    }
                }
                assertFalse(batch.tryFinish());
                assertTrue(events.stream().noneMatch(event -> event.startsWith("post:")));
                var resources = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(accepted));
                assertEquals(1, resources.sources().size());
                for (var source : resources.sources()) {
                    assertTrue(lane.submit(source::apply).get(5, TimeUnit.SECONDS));
                }
                assertTrue(resources.commit(() -> true));
                for (var source : resources.sources()) {
                    assertTrue(lane.submit(source::resolve).get(5, TimeUnit.SECONDS));
                }
                resources.finish();
                for (var stage : accepted) {
                    assertTrue(stage.settleBatch(null));
                }
            }
            assertTrue(batch.tryFinish());
        } finally {
            lane.shutdownNow();
            assertTrue(lane.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of(4, 2, 2), roundSizes);
        assertEquals(List.of("pre:blocked", "tick:blocked", "pre:failed", "tick:failed", "pre:left", "tick:left",
            "pre:right", "tick:right", "post:blocked", "post:failed", "post:left", "post:right"), events);
        assertSame(blocked, host.requireSlot("blocked").process());
        assertEquals(WorkSlotStatus.BLOCKED, host.requireSlot("blocked").status());
        assertSame(blockedResult, host.requireSlot("blocked").result());
        assertNull(failed.host());
        assertEquals(WorkSlotStatus.FAILED, host.requireSlot("failed").status());
        assertSame(failureResult, host.requireSlot("failed").result());
        assertNull(left.host());
        assertNull(right.host());
        assertEquals(92, energy.savedEnergy());
        host.snapshot();
    }

    @Test
    void preparedStopNeverRunsCallbacksOrRequirementsAndReturnsTheHostLease() {
        var machine = machine(2);
        var host = host(machine, "first", "second");
        var events = new ObjectArrayList<String>();
        var first = process(machine, "first", 1, requirement((_, _) -> {
            throw new AssertionError("Stopped preparation acquired work");
        }));
        var second = process(machine, "second", 1);
        for (var process : List.of(first, second)) {
            assertTrue(host.start(process));
            components(process, process.recipe().id().getPath(), events, NO_OP, NO_OP);
        }
        var batch = host.prepareTickBatch();
        assertThrows(IllegalStateException.class, first::tick);
        assertThrows(IllegalStateException.class, second::requirePersistenceBoundary);
        assertThrows(IllegalStateException.class, () -> host.detach(first));
        assertThrows(IllegalStateException.class, second::cancel);
        batch.stop();
        assertThrows(IllegalStateException.class, batch::begin);
        assertTrue(batch.tryFinish());
        assertTrue(events.isEmpty());
        assertTrue(host.detach(first));
        assertNull(first.host());
        assertSame(host, second.host());
        host.snapshot();
    }

    @Test
    void zeroCapacityKeepsExistingProcessesAndDiscardedRoundsNeverAcquireOrRestart() {
        var machine = machine(2);
        var host = host(machine, "first", "second");
        int[] acquisitions = {0};
        var observer = requirement((_, _) -> {
            acquisitions[0]++;
            return RequirementTransaction.SUCCESS;
        });
        var first = process(machine, "first", 1, observer);
        var second = process(machine, "second", 1, observer);
        assertTrue(host.start(first));
        assertTrue(host.start(second));
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 0);
        assertFalse(host.start(process(machine, "extra", 1)));
        var stopped = host.prepareTickBatch();
        stopped.begin();
        assertEquals(2, stopped.nextRound().size());
        stopped.stop();
        assertFalse(stopped.tryFinish());
        for (var stage : stopped.currentStages()) {
            stage.discard();
        }
        assertTrue(stopped.nextRound().isEmpty());
        assertTrue(stopped.tryFinish());
        assertEquals(0, acquisitions[0]);
        assertEquals(List.of(first, second), host.activeProcesses());
        var running = host.prepareTickBatch();
        running.begin();
        settleAllSynchronously(running);
        assertTrue(running.tryFinish());
        assertEquals(6, acquisitions[0]);
        assertEquals(1, first.state().successfulTicks());
        assertEquals(1, second.state().successfulTicks());
        assertTrue(host.activeProcesses().isEmpty());
    }

    @Test
    void capturedProcessFactsDoNotAuthorizeTickSaveDetachCancelOrAnotherSessionsPost() throws Exception {
        var machine = machine(2);
        var host = host(machine, "first", "second");
        var events = new ObjectArrayList<String>();
        var first = process(machine, "first", 1);
        var second = process(machine, "second", 1);
        assertTrue(host.start(first));
        assertTrue(host.start(second));
        components(first, "first", events, () -> {
            assertThrows(IllegalStateException.class, second::tick);
            assertThrows(IllegalStateException.class, second::requirePersistenceBoundary);
            assertThrows(IllegalStateException.class, () -> second.detachFrom(host));
            assertThrows(IllegalStateException.class, second::cancel);
        }, NO_OP);
        components(second, "second", events,
            () -> assertThrows(IllegalStateException.class, () -> Objects.requireNonNull(first.pendingTick()).finish()), NO_OP);
        var batch = host.prepareTickBatch();
        assertTrue(first.quiescent());
        assertTrue(second.persistenceReady());
        assertThrows(IllegalStateException.class, first::requireQuiescent);
        assertThrows(IllegalStateException.class, host::snapshot);
        @SuppressWarnings("resource")
        var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            pool.submit(() -> {
                assertThrows(IllegalStateException.class, batch::begin);
                assertThrows(IllegalStateException.class, batch::tryFinish);
                assertThrows(IllegalStateException.class, first::tick);
            }).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        batch.begin();
        assertThrows(IllegalStateException.class, () -> Objects.requireNonNull(first.pendingTick()).finish());
        batch.stop();
        assertTrue(batch.tryFinish());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "tick:second", "post:first", "post:second"), events);
        first.requirePersistenceBoundary();
        second.requirePersistenceBoundary();
        host.snapshot();
    }

    @Test
    void laterPreFailureKeepsEarlierPostAndLeavesUntouchedSlotsWithoutPost() {
        var machine = machine(3);
        var host = host(machine, "first", "second", "third");
        var events = new ObjectArrayList<String>();
        var first = process(machine, "first", 1);
        var second = process(machine, "second", 1);
        var third = process(machine, "third", 1);
        for (var process : List.of(first, second, third)) {
            assertTrue(host.start(process));
        }
        var primary = new IllegalStateException("second pre failed");
        components(first, "first", events, NO_OP, NO_OP);
        components(second, "second", events, () -> {
            throw primary;
        }, NO_OP);
        components(third, "third", events, NO_OP, NO_OP);
        var batch = host.prepareTickBatch();
        assertSame(primary, assertThrows(IllegalStateException.class, batch::begin));
        assertSame(batch, host.pendingTickBatch());
        assertTrue(batch.tryFinish());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "post:first"), events);
        assertNull(host.pendingTickBatch());
        host.requireQuiescent();
        assertThrows(IllegalStateException.class, host::snapshot);
        assertEquals(List.of(first, second, third), host.activeProcesses());
    }

    @Test
    void postFaultsAggregateWithoutPreventingHealthyTerminalSlotsFromDetaching() {
        var machine = machine(3);
        var host = host(machine, "first", "second", "third");
        var events = new ObjectArrayList<String>();
        var first = process(machine, "first", 0);
        var second = process(machine, "second", 0);
        var third = process(machine, "third", 0);
        for (var process : List.of(first, second, third)) {
            assertTrue(host.start(process));
        }
        var primary = new IllegalStateException("first post");
        var cleanup = new AssertionError("second post");
        components(first, "first", events, NO_OP, () -> {
            throw primary;
        });
        components(second, "second", events, NO_OP, () -> {
            throw cleanup;
        });
        components(third, "third", events, NO_OP, NO_OP);
        var batch = host.prepareTickBatch();
        batch.begin();
        settleAllSynchronously(batch);
        assertSame(primary, assertThrows(IllegalStateException.class, batch::tryFinish));
        assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
        assertTrue(batch.finished());
        assertNull(host.pendingTickBatch());
        assertTrue(events.containsAll(List.of("post:first", "post:second", "post:third")));
        assertSame(host, first.host());
        assertSame(host, second.host());
        assertNull(third.host());
        assertNull(host.requireSlot("third").process());
        host.requireQuiescent();
        assertThrows(IllegalStateException.class, batch::tryFinish);
    }

    @Test
    void unknownSourceOrBusinessPinsTheWholeHostUntilExactConfirmation() {
        for (boolean sourceFault : new boolean[]{true, false}) {
            var machine = machine(2);
            var host = host(machine, "first", "second");
            var events = new ObjectArrayList<String>();
            var energy = new EnergyBuffer(100, NO_OP);
            energy.receiveEnergy(100, Actionable.MODULATE);
            boolean[] recoverable = {false};
            int[] attempts = {0};
            int[] commits = {0};
            var primary = new IllegalStateException(sourceFault ? "source pending" : "business pending");
            var first = process(machine, "first", 1, requirement((_, _) -> {
                var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 10).reservation());
                ResourceReservation observed = sourceFault ? delayedSource(token, recoverable, attempts, primary) : token;
                return new RequirementTransaction() {
                    private boolean completed;

                    @Override
                    public ProcessResult result() {
                        return ProcessResult.SUCCESS;
                    }

                    @Override
                    public List<? extends ResourceReservation> reservations() {
                        return List.of(observed);
                    }

                    @Override
                    public void commit() {
                        commits[0]++;
                    }

                    @Override
                    public void complete() {
                        if (!sourceFault) {
                            attempts[0]++;
                            if (!recoverable[0]) {
                                throw primary;
                            }
                        }
                        completed = true;
                    }

                    @Override
                    public void rollback() {
                        throw new AssertionError("Completion was already decided");
                    }

                    @Override
                    public Resolution resolution() {
                        return completed ? Resolution.COMPLETED : recoverable[0] ? Resolution.REVERSIBLE : Resolution.UNKNOWN;
                    }
                };
            }));
            var second = process(machine, "second", 1);
            assertTrue(host.start(first));
            assertTrue(host.start(second));
            components(first, "first", events, NO_OP, NO_OP);
            components(second, "second", events, NO_OP, NO_OP);
            var batch = host.prepareTickBatch();
            batch.begin();
            var stages = batch.nextRound();
            for (var stage : stages) {
                stage.acquire();
            }
            var resources = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(stages));
            var source = resources.sources().getFirst();
            assertTrue(source.apply());
            if (sourceFault) {
                assertTrue(resources.commit(() -> true));
                assertSame(primary, assertThrows(IllegalStateException.class, source::resolve));
                assertFalse(source.resolve());
            } else {
                assertSame(primary, assertThrows(IllegalStateException.class, () -> resources.commit(() -> true)));
                assertFalse(resources.resolveBusiness());
            }
            batch.recordFailure(primary);
            for (var stage : stages) {
                assertFalse(stage.settleBatch(primary));
            }
            assertFalse(batch.tryFinish());
            assertSame(batch, host.pendingTickBatch());
            assertThrows(IllegalStateException.class, host::requireQuiescent);
            assertTrue(events.stream().noneMatch(event -> event.startsWith("post:")));
            assertEquals(1, attempts[0]);
            recoverable[0] = true;
            if (!sourceFault) {
                assertTrue(resources.resolveBusiness());
            }
            assertTrue(source.resolve());
            resources.finish();
            for (var stage : stages) {
                assertTrue(stage.settleBatch(null));
            }
            assertTrue(batch.nextRound().isEmpty());
            assertTrue(batch.tryFinish());
            assertEquals(2, attempts[0]);
            assertEquals(1, commits[0]);
            assertEquals(90, energy.savedEnergy());
            assertEquals(List.of("pre:first", "tick:first", "pre:second", "tick:second", "post:first", "post:second"), events);
            assertSame(host, first.host());
            assertSame(host, second.host());
            host.requireQuiescent();
            assertThrows(IllegalStateException.class, host::snapshot);
        }
    }

    @Test
    void partialRoundPreparationKeepsTheEarlierLeaseUntilDiscardWithoutAcquiringWork() {
        var machine = machine(2);
        var host = host(machine, "first", "second");
        var events = new ObjectArrayList<String>();
        var definition = new MachineRecipe(id("running"), 2, List.of(requirement((_, _) -> {
            throw new AssertionError("Prepared round must not acquire after later preparation failed");
        })));
        var first = RecipeProcess.restored(machine, definition, 1, ProbabilityTuning.defaultTuning(),
            new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 0, 0, ProcessResult.SUCCESS));
        var second = RecipeProcess.restored(machine, definition, 2, ProbabilityTuning.defaultTuning(),
            new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 0, Long.MAX_VALUE, ProcessResult.SUCCESS));
        assertTrue(host.start(first));
        assertTrue(host.start(second));
        components(first, "first", events, NO_OP, NO_OP);
        components(second, "second", events, NO_OP, NO_OP);
        var batch = host.prepareTickBatch();
        batch.begin();
        assertThrows(ArithmeticException.class, batch::nextRound);
        assertEquals(1, batch.currentStages().size());
        assertFalse(batch.tryFinish());
        batch.currentStages().getFirst().discard();
        assertTrue(batch.tryFinish());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "tick:second", "post:first", "post:second"), events);
        host.requireQuiescent();
        assertThrows(IllegalStateException.class, host::snapshot);
    }

    @Test
    void customSynchronousExecutorRemainsTheSelectedDriver() {
        var machine = machine(1);
        int[] calls = {0};
        var host = new MachineWorkSlots(machine, List.of(), Set.of(), process -> {
            calls[0]++;
            process.tick();
        });
        var process = process(machine, "custom", 2);
        assertTrue(host.start(process));
        assertThrows(IllegalStateException.class, host::prepareTickBatch);
        assertNull(host.pendingTickBatch());
        host.tickProcesses();
        assertEquals(1, calls[0]);
        assertEquals(1, process.state().successfulTicks());
        host.snapshot();
    }

    @Test
    void rawAcquisitionAndCrossProcessStageCallbacksCannotBypassTheHostLease() {
        var machine = machine(3);
        var host = host(machine, "first", "second", "third");
        var energy = new EnergyBuffer(100, NO_OP);
        energy.receiveEnergy(100, Actionable.MODULATE);
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy), List.of()));
        var events = new ObjectArrayList<String>();
        RecipeProcess.Stage[] foreign = {null};
        int[] firstAcquisitions = {0};
        int[] secondAcquisitions = {0};
        var io = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 0, 0, 0, false));
        var first = process(machine, "first", 1, io, requirement((_, _) -> {
            firstAcquisitions[0]++;
            if (foreign[0] != null) {
                assertThrows(IllegalStateException.class, foreign[0]::acquire);
            }
            return RequirementTransaction.SUCCESS;
        }));
        var second = process(machine, "second", 1, requirement((_, _) -> {
            secondAcquisitions[0]++;
            return RequirementTransaction.SUCCESS;
        }));
        var third = process(machine, "third", 1);
        for (var process : List.of(first, second, third)) {
            assertTrue(host.start(process));
        }
        components(first, "first", events, NO_OP, NO_OP);
        components(second, "second", events,
            () -> assertThrows(IllegalStateException.class, () -> Objects.requireNonNull(first.pendingTick()).nextStage()),
            () -> assertThrows(IllegalStateException.class, () -> Objects.requireNonNull(third.pendingTick()).nextStage()));
        components(third, "third", events, NO_OP, NO_OP);
        var batch = host.prepareTickBatch();
        assertThrows(IllegalStateException.class, () -> RecipeStageExecutor.execute(first, RecipeStage.START));
        assertEquals(100, energy.savedEnergy());
        assertEquals(100, energy.energySnapshot().availableEnergy());
        assertEquals(0, firstAcquisitions[0]);
        batch.begin();
        var stages = batch.nextRound();
        foreign[0] = stages.get(1);
        stages.getFirst().acquire();
        assertEquals(0, secondAcquisitions[0]);
        stages.get(1).acquire();
        stages.get(2).acquire();
        var resources = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(stages));
        assertTrue(resources.sources().getFirst().apply());
        assertTrue(resources.commit(() -> true));
        assertTrue(resources.sources().getFirst().resolve());
        resources.finish();
        for (var stage : stages) {
            assertTrue(stage.settleBatch(null));
        }
        batch.stop();
        assertTrue(batch.tryFinish());
        assertEquals(1, firstAcquisitions[0]);
        assertEquals(1, secondAcquisitions[0]);
        assertEquals(98, energy.savedEnergy());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "tick:second", "pre:third", "tick:third",
            "post:first", "post:second", "post:third"), events);
        host.snapshot();
    }
}
