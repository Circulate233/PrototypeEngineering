package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
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

class MachineWorkSlotGroupTest {
    private static final Runnable NO_OP = () -> {};

    @Test
    void sharedSourceRoundsExecuteEachPhaseOnceAcrossRealHosts() {
        var energy = new EnergyBuffer(100, NO_OP);
        energy.receiveEnergy(100, Actionable.MODULATE);
        var firstHost = host();
        var secondHost = host();
        for (var host : List.of(firstHost, secondHost)) {
            host.owner().components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy), List.of(energy)));
        }
        var io = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 1, false));
        var first = process(firstHost, "first", 1, io);
        var second = process(secondHost, "second", 1, io);
        var events = new ObjectArrayList<String>();
        components(first, "first", events, NO_OP, NO_OP);
        components(second, "second", events, NO_OP, NO_OP);
        var group = MachineWorkSlots.prepareTickGroup(List.of(firstHost, secondHost));
        assertTrue(events.isEmpty());
        assertThrows(IllegalStateException.class, secondHost::snapshot);
        group.begin();
        var phases = new ObjectArrayList<RecipeStage>();
        List<RecipeProcess.Stage> stages;
        while (!(stages = group.nextRound()).isEmpty()) {
            assertEquals(2, stages.size());
            phases.add(stages.getFirst().phase());
            for (var stage : stages) { stage.acquire(); }
            settleBatch(stages);
        }
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
        assertTrue(group.tryFinish());
        assertEquals(92, energy.savedEnergy());
        assertEquals(1, first.state().successfulTicks());
        assertEquals(1, second.state().successfulTicks());
        assertNull(first.host());
        assertNull(second.host());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "tick:second", "post:first", "post:second"), events);
        firstHost.snapshot();
        secondHost.snapshot();
    }

    @Test
    void validationRejectsLaterCustomOrInvalidHostsWithoutInstallingAnyLease() {
        var first = host();
        process(first, "first", 1);
        var custom = new MachineWorkSlots(new MachineInstance(), List.of(), Set.of(), RecipeProcess::tick);
        var invalid = host();
        process(invalid, "invalid", 1).invalidatePersistenceBoundary();
        for (var hosts : List.of(List.of(first, custom), List.of(first, invalid), List.of(first, first))) {
            assertThrows(RuntimeException.class, () -> MachineWorkSlots.prepareTickGroup(hosts));
            assertNull(first.pendingTickBatch());
            assertNull(custom.pendingTickBatch());
            assertNull(invalid.pendingTickBatch());
            first.snapshot();
        }
        var ordinary = first.prepareTickBatch();
        assertTrue(ordinary.tryFinish());
    }

    @Test
    void blockedAndFailedHostsDoNotPreventAnotherHostFromCompletingItsLaterRounds() {
        var blockedHost = host();
        var failedHost = host();
        var successfulHost = host();
        var blockedResult = new ProcessResult.Blocked("waiting");
        var failureResult = new ProcessResult.Failure("rejected");
        var blocked = process(blockedHost, "blocked", 0, requirement((_, _) -> outcome(blockedResult)));
        var failed = process(failedHost, "failed", 0, requirement((_, _) -> outcome(failureResult)));
        var successful = process(successfulHost, "successful", 0);
        var events = new ObjectArrayList<String>();
        components(blocked, "blocked", events, NO_OP, NO_OP);
        components(failed, "failed", events, NO_OP, NO_OP);
        components(successful, "successful", events, NO_OP, NO_OP);
        var group = MachineWorkSlots.prepareTickGroup(List.of(blockedHost, failedHost, successfulHost));
        group.begin();
        var first = group.nextRound();
        assertEquals(3, first.size());
        for (var stage : first) { stage.acquire(); stage.settleSynchronously(); }
        var next = group.nextRound();
        assertEquals(1, next.size());
        assertEquals(RecipeStage.END, next.getFirst().phase());
        next.getFirst().acquire();
        next.getFirst().settleSynchronously();
        assertTrue(group.nextRound().isEmpty());
        assertTrue(group.tryFinish());
        assertSame(blockedHost, blocked.host());
        assertEquals(RecipeProcess.Lifecycle.NEW, blocked.state().lifecycle());
        assertSame(blockedResult, blocked.state().result());
        assertNull(failed.host());
        assertSame(failureResult, failedHost.requireSlot("main").result());
        assertNull(successful.host());
        assertEquals(3, events.stream().filter(event -> event.startsWith("post:")).count());
        blockedHost.snapshot();
        failedHost.snapshot();
        successfulHost.snapshot();
    }

    @Test
    void preStageBusinessAndPostCannotEnterAnotherHostOrReleaseAnEarlierHost() {
        var firstHost = host();
        var secondHost = host();
        var events = new ObjectArrayList<String>();
        RecipeProcess.Stage[] foreign = {null};
        int[] acquisitions = {0};
        var first = process(firstHost, "first", 2, requirement((_, _) -> {
            if (foreign[0] != null) { assertThrows(IllegalStateException.class, foreign[0]::acquire); }
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public void commit() { assertThrows(IllegalStateException.class, foreign[0]::discard); }
                @Override public void rollback() { }
            };
        }));
        var second = process(secondHost, "second", 2, requirement((_, _) -> {
            acquisitions[0]++;
            return RequirementTransaction.SUCCESS;
        }));
        components(first, "first", events, () -> {
            assertThrows(IllegalStateException.class, second::tick);
            assertThrows(IllegalStateException.class, second::requirePersistenceBoundary);
            assertThrows(IllegalStateException.class, () -> second.detachFrom(secondHost));
            assertThrows(IllegalStateException.class, second::cancel);
        }, () -> assertThrows(IllegalStateException.class, () -> Objects.requireNonNull(second.pendingTick()).nextStage()));
        components(second, "second", events,
            () -> assertThrows(IllegalStateException.class, () -> Objects.requireNonNull(first.pendingTick()).nextStage()),
            () -> {
                assertTrue(Objects.requireNonNull(firstHost.pendingTickBatch()).finished());
                assertThrows(IllegalStateException.class, first::tick);
                assertThrows(IllegalStateException.class, firstHost::snapshot);
                assertThrows(IllegalStateException.class, firstHost::tickProcesses);
                assertThrows(IllegalStateException.class, firstHost::prepareTickBatch);
            });
        var group = MachineWorkSlots.prepareTickGroup(List.of(firstHost, secondHost));
        group.begin();
        var stages = group.nextRound();
        foreign[0] = stages.get(1);
        stages.getFirst().acquire();
        assertEquals(0, acquisitions[0]);
        stages.get(1).acquire();
        for (var stage : stages) { stage.settleSynchronously(); }
        group.stop();
        assertTrue(group.tryFinish());
        assertEquals(1, acquisitions[0]);
        assertEquals(0, first.state().successfulTicks());
        assertEquals(0, second.state().successfulTicks());
        firstHost.snapshot();
        secondHost.snapshot();
    }

    @Test
    void laterPreFailureRetainsEarlierSessionAndNeverRunsUntouchedHostCallbacks() {
        var firstHost = host();
        var secondHost = host();
        var untouchedHost = host();
        var events = new ObjectArrayList<String>();
        var first = process(firstHost, "first", 1);
        var second = process(secondHost, "second", 1);
        var untouched = process(untouchedHost, "untouched", 1);
        var failure = new IllegalStateException("later pre failed");
        components(first, "first", events, () -> assertThrows(IllegalStateException.class, untouchedHost::snapshot), NO_OP);
        components(second, "second", events, () -> { throw failure; }, NO_OP);
        components(untouched, "untouched", events, NO_OP, NO_OP);
        var group = MachineWorkSlots.prepareTickGroup(List.of(firstHost, secondHost, untouchedHost));
        assertSame(failure, assertThrows(IllegalStateException.class, group::begin));
        assertFalse(group.finished());
        assertTrue(group.currentStages().isEmpty());
        assertTrue(group.tryFinish());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "post:first"), events);
        assertSame(firstHost, first.host());
        assertSame(secondHost, second.host());
        assertSame(untouchedHost, untouched.host());
        firstHost.requireQuiescent();
        secondHost.requireQuiescent();
        untouchedHost.snapshot();
    }

    @Test
    void laterStagePreparationFailurePreservesEarlierHostStageForDiscard() {
        var firstHost = host();
        var secondHost = host();
        var definition = new MachineRecipe(id("running"), 2, List.of(requirement((_, _) -> {
            throw new AssertionError("Partial prepared stage must never acquire");
        })));
        var first = RecipeProcess.restored(firstHost.owner(), definition, 1, ProbabilityTuning.defaultTuning(),
            new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 0, 0, ProcessResult.SUCCESS));
        var second = RecipeProcess.restored(secondHost.owner(), definition, 2, ProbabilityTuning.defaultTuning(),
            new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 0, Long.MAX_VALUE, ProcessResult.SUCCESS));
        assertTrue(firstHost.start(first));
        assertTrue(secondHost.start(second));
        var events = new ObjectArrayList<String>();
        components(first, "first", events, NO_OP, NO_OP);
        components(second, "second", events, NO_OP, NO_OP);
        var group = MachineWorkSlots.prepareTickGroup(List.of(firstHost, secondHost));
        group.begin();
        assertThrows(ArithmeticException.class, group::nextRound);
        assertEquals(1, group.currentStages().size());
        assertFalse(group.tryFinish());
        group.currentStages().getFirst().discard();
        assertTrue(group.tryFinish());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "tick:second", "post:first", "post:second"), events);
        firstHost.requireQuiescent();
        secondHost.requireQuiescent();
    }

    @Test
    void unknownSourceKeepsEveryHostAndEveryPostPendingUntilExactConfirmation() {
        var firstHost = host();
        var secondHost = host();
        var energy = new EnergyBuffer(100, NO_OP);
        energy.receiveEnergy(100, Actionable.MODULATE);
        boolean[] recoverable = {false};
        int[] attempts = {0};
        var failure = new IllegalStateException("unknown source");
        var first = process(firstHost, "first", 1, requirement((_, _) -> {
            var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 10).reservation());
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public List<? extends ResourceReservation> reservations() { return List.of(delayed(token, recoverable, attempts, failure)); }
                @Override public void commit() { }
                @Override public void rollback() { }
            };
        }));
        var second = process(secondHost, "second", 1);
        var events = new ObjectArrayList<String>();
        components(first, "first", events, NO_OP, NO_OP);
        components(second, "second", events, NO_OP, NO_OP);
        var group = MachineWorkSlots.prepareTickGroup(List.of(firstHost, secondHost));
        group.begin();
        var stages = group.nextRound();
        for (var stage : stages) { stage.acquire(); }
        var resources = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(stages));
        var source = resources.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(resources.commit(() -> true));
        assertSame(failure, assertThrows(IllegalStateException.class, source::resolve));
        assertFalse(source.resolve());
        group.recordFailure(failure);
        for (var stage : stages) { assertFalse(stage.settleBatch(failure)); }
        assertFalse(group.tryFinish());
        assertTrue(events.stream().noneMatch(event -> event.startsWith("post:")));
        for (var host : List.of(firstHost, secondHost)) {
            assertThrows(IllegalStateException.class, host::requireQuiescent);
            assertThrows(IllegalStateException.class, host::snapshot);
        }
        assertEquals(1, attempts[0]);
        recoverable[0] = true;
        assertTrue(source.resolve());
        resources.finish();
        for (var stage : stages) { assertTrue(stage.settleBatch(null)); }
        assertTrue(group.nextRound().isEmpty());
        assertTrue(group.tryFinish());
        assertEquals(2, attempts[0]);
        assertEquals(90, energy.savedEnergy());
        assertEquals(List.of("pre:first", "tick:first", "pre:second", "tick:second", "post:first", "post:second"), events);
        firstHost.requireQuiescent();
        secondHost.requireQuiescent();
    }

    @Test
    void postFaultsKeepFinishedHostLeasedAndDoNotPreventOtherHealthyTerminalDetaches() {
        var hosts = List.of(host(), host(), host(), host());
        var processes = new ObjectArrayList<RecipeProcess>();
        var events = new ObjectArrayList<String>();
        for (int i = 0; i < hosts.size(); i++) { processes.add(process(hosts.get(i), "p" + i, 0)); }
        var failure = new IllegalStateException("later post");
        var cleanup = new AssertionError("third post");
        components(processes.getFirst(), "first", events, NO_OP, NO_OP);
        components(processes.get(1), "second", events, NO_OP, () -> {
            assertNull(processes.getFirst().host());
            assertTrue(Objects.requireNonNull(hosts.getFirst().pendingTickBatch()).finished());
            assertThrows(IllegalStateException.class, hosts.getFirst()::tickProcesses);
            assertThrows(IllegalStateException.class, hosts.getFirst()::snapshot);
            throw failure;
        });
        components(processes.get(2), "third", events, NO_OP, () -> { throw cleanup; });
        components(processes.getLast(), "fourth", events, NO_OP, NO_OP);
        var group = MachineWorkSlots.prepareTickGroup(hosts);
        group.begin();
        settleSynchronously(group);
        assertSame(failure, assertThrows(IllegalStateException.class, group::tryFinish));
        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
        assertTrue(group.finished());
        assertNull(processes.getFirst().host());
        assertNull(processes.getLast().host());
        assertSame(hosts.get(1), processes.get(1).host());
        assertSame(hosts.get(2), processes.get(2).host());
        for (var host : hosts) { assertNull(host.pendingTickBatch()); host.requireQuiescent(); }
        assertEquals(4, events.stream().filter(event -> event.startsWith("post:")).count());
        hosts.getFirst().snapshot();
        hosts.getLast().snapshot();
    }

    @Test
    void groupRejectsOtherThreadsChildControlAndStaleHandlesWhileOrdinaryBatchStillWorks() throws Exception {
        var firstHost = host();
        var secondHost = host();
        var first = process(firstHost, "first", 2);
        process(secondHost, "second", 2);
        var group = MachineWorkSlots.prepareTickGroup(List.of(firstHost, secondHost));
        var child = Objects.requireNonNull(firstHost.pendingTickBatch());
        assertThrows(IllegalStateException.class, child::begin);
        assertThrows(IllegalStateException.class, child::tryFinish);
        @SuppressWarnings("resource")
        var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            pool.submit(() -> {
                assertThrows(IllegalStateException.class, group::begin);
                assertThrows(IllegalStateException.class, group::currentStages);
                assertThrows(IllegalStateException.class, group::tryFinish);
                assertThrows(IllegalStateException.class, first::tick);
            }).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        group.begin();
        group.stop();
        assertTrue(group.tryFinish());
        assertThrows(IllegalStateException.class, group::begin);
        assertThrows(IllegalStateException.class, group::nextRound);
        assertThrows(IllegalStateException.class, group::currentStages);
        assertThrows(IllegalStateException.class, group::stop);
        assertThrows(IllegalStateException.class, () -> group.recordFailure(new AssertionError("stale")));
        assertThrows(IllegalStateException.class, group::tryFinish);
        assertThrows(IllegalStateException.class, child::begin);
        var ordinary = firstHost.prepareTickBatch();
        ordinary.begin();
        ordinary.stop();
        assertTrue(ordinary.tryFinish());
        firstHost.snapshot();
        var empty = MachineWorkSlots.prepareTickGroup(List.of());
        empty.begin();
        assertTrue(empty.nextRound().isEmpty());
        assertTrue(empty.tryFinish());
    }

    private static void settleBatch(List<RecipeProcess.Stage> stages) {
        var resources = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(stages));
        for (var source : resources.sources()) { assertTrue(source.apply()); }
        assertTrue(resources.commit(() -> true));
        for (var source : resources.sources()) { assertTrue(source.resolve()); }
        resources.finish();
        for (var stage : stages) { assertTrue(stage.settleBatch(null)); }
    }

    private static void settleSynchronously(MachineWorkSlots.TickGroup group) {
        List<RecipeProcess.Stage> stages;
        while (!(stages = group.nextRound()).isEmpty()) {
            for (var stage : stages) { stage.acquire(); stage.settleSynchronously(); }
        }
    }

    private static ResourceReservation delayed(ResourceReservation token, boolean[] recoverable, int[] attempts, RuntimeException failure) {
        return new ResourceReservation() {
            @Override public StorageContext context() { return token.context(); }
            @Override public boolean canApply() { return token.canApply(); }
            @Override public void apply() { token.apply(); }
            @Override public void complete() { attempts[0]++; if (!recoverable[0]) { throw failure; } token.complete(); }
            @Override public void rollback() { token.rollback(); }
            @Override public Resolution resolution() { return recoverable[0] ? token.resolution() : Resolution.UNKNOWN; }
        };
    }

    private static MachineWorkSlots host() { return new MachineWorkSlots(new MachineInstance(), List.of()); }

    private static RequirementTransaction outcome(ProcessResult result) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return result; }
            @Override public void commit() { }
            @Override public void rollback() { }
        };
    }

    private static RecipeProcess process(MachineWorkSlots host, String name, int duration, RecipeRequirement<?>... requirements) {
        var process = new RecipeProcess(host.owner(), new MachineRecipe(id(name), duration, List.of(requirements)), 1);
        assertTrue(host.start(process));
        return process;
    }

    private static RecipeRequirement<String> requirement(BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.START); }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.TICK); }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.END); }
        };
        return new RecipeRequirementType<>(id("observer"), system).requirement(id("observer"), "value");
    }

    private static void components(RecipeProcess process, String name, List<String> events, Runnable pre, Runnable post) {
        process.components().create(new ComponentType<>(id("component"), _ -> "value", List.of(new ComponentSystem<RecipeProcess, String>() {
            @Override public ResourceLocation id() { return MachineWorkSlotGroupTest.id("system"); }
            @Override public void onPreTick(RecipeProcess owner, String component) { events.add("pre:" + name); pre.run(); }
            @Override public void onTick(RecipeProcess owner, String component) { events.add("tick:" + name); }
            @Override public void onPostTick(RecipeProcess owner, String component) { events.add("post:" + name); post.run(); }
        })), process);
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("work_slot_group_test", name); }
}
