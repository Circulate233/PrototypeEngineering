package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class RecipeTickSessionTest {
    @Test void realStagesSettleOnSourceLanesBeforeTheNextFrozenTimelineAndOnePost() throws Exception {
        var energy = energy();
        var machine = machine(energy);
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0);
        var events = new ObjectArrayList<String>();
        var process = process(machine, 1, energyRequirement(), observer((current, phase) -> {
            events.add(phase.name());
            if (phase == RecipeStage.START) {
                return new RequirementTransaction() {
                    @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                    @Override public void commit() { current.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 1); }
                    @Override public void rollback() { current.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0); }
                };
            }
            if (phase == RecipeStage.TICK) { assertEquals(new RecipeProcess.Tick(0, 1, 0, 1), current.tickContext()); }
            return RequirementTransaction.SUCCESS;
        }));
        components(process, events);
        var session = Objects.requireNonNull(process.beginTick());
        try (var lane = Executors.newSingleThreadExecutor()) {
            for (var phase : RecipeStage.values()) {
                var stage = Objects.requireNonNull(session.nextStage());
                assertEquals(phase, stage.phase());
                stage.acquire();
                var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage)));
                assertThrows(IllegalStateException.class, session::nextStage);
                assertThrows(IllegalStateException.class, session::finish);
                assertFalse(stage.settleBatch(null));
                for (var source : batch.sources()) { assertTrue(lane.submit(source::apply).get(5, TimeUnit.SECONDS)); }
                assertTrue(batch.commit(() -> true));
                assertFalse(stage.settleBatch(null), "Business completion alone does not release the stage");
                for (var source : batch.sources()) { assertTrue(lane.submit(source::resolve).get(5, TimeUnit.SECONDS)); }
                batch.finish();
                assertTrue(stage.settleBatch(null));
                assertThrows(IllegalStateException.class, stage::acquire);
                assertFalse(events.contains("post"));
            }
        }
        assertNull(session.nextStage());
        session.finish();
        assertEquals(List.of("pre", "component", "START", "TICK", "END", "post"), events);
        assertEquals(96, energy.savedEnergy());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        process.requirePersistenceBoundary();
    }

    @Test void zeroSpeedStillExecutesOnlyOneTickAndOldHandlesCannotReenterTheNextSession() {
        var process = process(new MachineInstance(), 2);
        process.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0);
        var first = Objects.requireNonNull(process.beginTick());
        var start = Objects.requireNonNull(first.nextStage());
        start.acquire();
        assertSame(ProcessResult.SUCCESS, start.settleSynchronously());
        var tick = Objects.requireNonNull(first.nextStage());
        assertEquals(RecipeStage.TICK, tick.phase());
        tick.acquire();
        assertSame(ProcessResult.SUCCESS, tick.settleSynchronously());
        assertNull(first.nextStage());
        first.finish();
        assertEquals(0, process.state().progress());
        assertEquals(1, process.state().successfulTicks());
        var second = Objects.requireNonNull(process.beginTick());
        assertThrows(IllegalStateException.class, first::nextStage);
        assertThrows(IllegalStateException.class, tick::settleSynchronously);
        assertEquals(RecipeStage.TICK, Objects.requireNonNull(second.nextStage()).phase());
        var current = Objects.requireNonNull(second.currentStage());
        current.acquire();
        current.rollbackSynchronously();
        second.finish();
        process.requirePersistenceBoundary();
    }

    @Test void zeroDurationHasStartAndEndWithoutInventingATick() {
        var phases = new ObjectArrayList<RecipeStage>();
        var process = process(new MachineInstance(), 0, observer((_, phase) -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        }));
        var session = Objects.requireNonNull(process.beginTick());
        RecipeProcess.Stage stage;
        while ((stage = session.nextStage()) != null) {
            stage.acquire();
            assertSame(ProcessResult.SUCCESS, stage.settleSynchronously());
        }
        session.finish();
        assertEquals(List.of(RecipeStage.START, RecipeStage.END), phases);
        assertEquals(0, process.state().successfulTicks());
    }

    @Test void sharedRoundCompensatesBothProcessesAndPreservesExactSourceIdentity() {
        var energy = energy();
        var first = process(machine(energy), 1, energyRequirement());
        var primary = new IllegalStateException("second commit");
        var second = process(machine(energy), 1, energyRequirement(), observer((_, _) -> new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public void commit() { throw primary; }
            @Override public void rollback() { }
        }));
        var sessions = List.of(Objects.requireNonNull(first.beginTick()), Objects.requireNonNull(second.beginTick()));
        var stages = new ObjectArrayList<RecipeProcess.Stage>();
        for (var session : sessions) {
            var stage = Objects.requireNonNull(session.nextStage());
            stage.acquire();
            stages.add(stage);
        }
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(stages));
        assertEquals(1, batch.sources().size(), "Both stages enroll the same physical energy domain");
        assertTrue(batch.sources().getFirst().apply());
        assertSame(primary, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertTrue(batch.rollback());
        assertTrue(batch.sources().getFirst().resolve());
        for (var stage : stages) { assertFalse(stage.settleBatch(primary)); }
        batch.finish();
        for (var stage : stages) { assertTrue(stage.settleBatch(primary)); }
        for (var session : sessions) { assertNull(session.nextStage()); session.finish(); }
        assertEquals(100, energy.savedEnergy());
        for (var process : List.of(first, second)) {
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            process.requireQuiescent();
            assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
        }
    }

    @Test void ordinaryFaultPostsImmediatelyButDeferredFaultWaitsForExactBusinessConfirmation() {
        for (boolean deferred : new boolean[]{false, true}) {
            var events = new ObjectArrayList<String>();
            var effect = new RecoverableEffect();
            var process = process(new MachineInstance(), 1, observer((_, _) -> effect));
            components(process, events);
            RecipeProcess.TickSession session;
            RecipeProcess.Stage stage;
            ResourceBatch batch = null;
            if (deferred) {
                session = Objects.requireNonNull(process.beginTick());
                stage = Objects.requireNonNull(session.nextStage());
                stage.acquire();
                batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage)));
                var bound = batch;
                assertSame(effect.failure, assertThrows(IllegalStateException.class, () -> bound.commit(() -> true)));
                assertFalse(stage.settleBatch(effect.failure));
                assertEquals(List.of("pre", "component"), events);
            } else {
                assertSame(effect.failure, assertThrows(IllegalStateException.class, process::tick));
                session = Objects.requireNonNull(process.pendingTick());
                stage = Objects.requireNonNull(session.currentStage());
                assertFalse(stage.resolveSynchronously());
                assertEquals(List.of("pre", "component", "post"), events);
            }
            assertThrows(IllegalStateException.class, process::requireQuiescent);
            assertThrows(IllegalStateException.class, session::finish);
            assertEquals(1, effect.completions);
            effect.recoverable = true;
            if (deferred) {
                assertTrue(Objects.requireNonNull(batch).resolveBusiness());
                batch.finish();
                assertTrue(stage.settleBatch(null));
            } else { assertTrue(stage.resolveSynchronously()); }
            assertNull(session.nextStage());
            session.finish();
            assertEquals(List.of("pre", "component", "post"), events);
            assertEquals(1, effect.commits);
            assertEquals(2, effect.completions);
            process.requireQuiescent();
            assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
        }
    }

    @Test void leaseRejectsCallbackReentryEvenWhenAllRequirementsReturnTheSuccessSingleton() {
        var events = new ObjectArrayList<String>();
        var process = process(new MachineInstance(), 0, observer((current, _) -> {
            var session = Objects.requireNonNull(current.pendingTick());
            assertThrows(IllegalStateException.class, session::nextStage);
            assertThrows(IllegalStateException.class, session::finish);
            assertThrows(IllegalStateException.class, current::tick);
            assertThrows(IllegalStateException.class, current::requirePersistenceBoundary);
            return RequirementTransaction.SUCCESS;
        }));
        components(process, events);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of("pre", "component", "post"), events);
        process.requirePersistenceBoundary();
    }

    @Test void rejectedStageRollsBackOnceAndDoesNotRepeatPreOrAttemptLaterStages() {
        var events = new ObjectArrayList<String>();
        var blocked = new ProcessResult.Blocked("wait");
        var process = process(new MachineInstance(), 1, observer((_, phase) -> {
            events.add(phase.name());
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return blocked; }
                @Override public void commit() { fail("Rejected stage committed"); }
                @Override public void rollback() { events.add("rollback"); }
            };
        }));
        components(process, events);
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        stage.acquire();
        assertSame(blocked, stage.result());
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage)));
        assertTrue(batch.rollback());
        batch.finish();
        assertTrue(stage.settleBatch(null));
        assertNull(session.nextStage());
        session.finish();
        assertSame(blocked, process.state().result());
        assertEquals(List.of("pre", "component", "START", "rollback", "post"), events);
    }

    @Test void sourceConfirmationPinsTheStageAfterEveryBusinessTransactionAlreadyCompleted() {
        var energy = energy();
        var events = new ObjectArrayList<String>();
        var recoverable = new boolean[1];
        var primary = new IllegalStateException("source completion pending");
        int[] completions = {0};
        var process = process(new MachineInstance(), 1, observer((_, _) -> {
            var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 10).reservation());
            var delayed = new ResourceReservation() {
                @Override public StorageContext context() { return token.context(); }
                @Override public boolean canApply() { return token.canApply(); }
                @Override public void apply() { token.apply(); }
                @Override public void complete() {
                    completions[0]++;
                    if (!recoverable[0]) { throw primary; }
                    token.complete();
                }
                @Override public void rollback() { token.rollback(); }
                @Override public Resolution resolution() { return recoverable[0] ? token.resolution() : Resolution.UNKNOWN; }
            };
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public List<? extends ResourceReservation> reservations() { return List.of(delayed); }
                @Override public void commit() { }
                @Override public void rollback() { }
            };
        }));
        components(process, events);
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        stage.acquire();
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage)));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.commit(() -> true));
        assertSame(primary, assertThrows(IllegalStateException.class, source::resolve));
        assertFalse(source.resolve());
        assertFalse(stage.settleBatch(primary));
        assertThrows(IllegalStateException.class, process::requireQuiescent);
        assertThrows(IllegalStateException.class, session::finish);
        assertEquals(List.of("pre", "component"), events);
        assertEquals(1, completions[0]);
        recoverable[0] = true;
        assertTrue(source.resolve());
        batch.finish();
        assertTrue(stage.settleBatch(null));
        session.finish();
        assertEquals(2, completions[0]);
        assertEquals(90, energy.savedEnergy());
        process.requireQuiescent();
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test void notificationFaultAfterReleaseStopsLaterRoundsWithoutReplayingCommittedResources() {
        boolean[] armed = {false};
        var primary = new IllegalStateException("notification");
        var energy = new EnergyBuffer(100, () -> { if (armed[0]) { throw primary; } });
        energy.receiveEnergy(100, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var process = process(machine(energy), 1, energyRequirement());
        components(process, events);
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        stage.acquire();
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage)));
        assertTrue(batch.sources().getFirst().apply());
        assertTrue(batch.commit(() -> true));
        assertTrue(batch.sources().getFirst().resolve());
        armed[0] = true;
        assertSame(primary, assertThrows(IllegalStateException.class, batch::finish));
        assertTrue(batch.finished());
        assertTrue(stage.settleBatch(primary));
        assertNull(session.nextStage());
        session.finish();
        assertEquals(List.of("pre", "component", "post"), events);
        assertEquals(98, energy.savedEnergy());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        process.requireQuiescent();
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test void partialAcquisitionFaultKeepsItsOriginalErrorAndRunsOrdinaryPostAfterCompensation() {
        var events = new ObjectArrayList<String>();
        var primary = new IllegalStateException("result failed");
        var process = process(new MachineInstance(), 1, observer((_, _) -> new RequirementTransaction() {
            @Override public ProcessResult result() { throw primary; }
            @Override public void commit() { fail("Faulted acquisition committed"); }
            @Override public void rollback() { events.add("rollback"); }
        }));
        components(process, events);
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(List.of("pre", "component", "rollback", "post"), events);
        process.requireQuiescent();
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test void preparedStageCanBeDiscardedByItsOwnerWithoutAcquiringAnyRequirement() throws Exception {
        var events = new ObjectArrayList<String>();
        var process = process(new MachineInstance(), 1, observer((_, _) -> {
            fail("Cancellation before acquisition must not execute a requirement");
            return RequirementTransaction.SUCCESS;
        }));
        components(process, events);
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        try (var foreign = Executors.newSingleThreadExecutor()) {
            foreign.submit(() -> assertThrows(IllegalStateException.class, stage::discard)).get(5, TimeUnit.SECONDS);
        }
        assertThrows(IllegalStateException.class, process::cancel);
        stage.discard();
        assertThrows(IllegalStateException.class, stage::acquire);
        assertNull(session.nextStage());
        session.finish();
        assertEquals(List.of("pre", "component", "post"), events);
        process.requirePersistenceBoundary();
        assertTrue(process.cancel());
    }

    private static RecipeProcess process(MachineInstance owner, int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(owner, new MachineRecipe(id("recipe"), duration, List.of(requirements)), 1);
    }

    private static MachineInstance machine(EnergyBuffer energy) {
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy), List.of(energy)));
        return machine;
    }

    private static EnergyBuffer energy() {
        var energy = new EnergyBuffer(100, () -> { });
        energy.receiveEnergy(100, Actionable.MODULATE);
        return energy;
    }

    private static RecipeRequirement<EnergyRequirement> energyRequirement() {
        return RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 1, false));
    }

    private static RecipeRequirement<String> observer(BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.START);
            }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.TICK);
            }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(id("observer"), system).requirement(id("observer"), "value");
    }

    private static void components(RecipeProcess process, List<String> events) {
        process.components().create(new ComponentType<>(id("component"), _ -> "value", List.of(new ComponentSystem<RecipeProcess, String>() {
            @Override public ResourceLocation id() { return RecipeTickSessionTest.id("component_system"); }
            @Override public void onPreTick(RecipeProcess owner, String component) {
                assertThrows(IllegalStateException.class, owner::beginTick);
                events.add("pre");
            }
            @Override public void onTick(RecipeProcess owner, String component) { events.add("component"); }
            @Override public void onPostTick(RecipeProcess owner, String component) {
                assertThrows(IllegalStateException.class, owner::beginTick);
                events.add("post");
            }
        })), process);
    }

    private static ResourceLocation id(String value) { return new ResourceLocation("tick_session_test", value); }

    private static final class RecoverableEffect implements RequirementTransaction {
        private final IllegalStateException failure = new IllegalStateException("pending completion");
        private boolean recoverable;
        private boolean completed;
        private int commits;
        private int completions;

        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public void commit() { commits++; }
        @Override public void complete() { completions++; if (!recoverable) { throw failure; } completed = true; }
        @Override public void rollback() { fail("Already decided completion"); }
        @Override public Resolution resolution() {
            return completed ? Resolution.COMPLETED : recoverable ? Resolution.REVERSIBLE : Resolution.UNKNOWN;
        }
    }
}
