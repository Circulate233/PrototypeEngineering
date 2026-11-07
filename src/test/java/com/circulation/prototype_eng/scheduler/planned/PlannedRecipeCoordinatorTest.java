package com.circulation.prototype_eng.scheduler.planned;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
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
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class PlannedRecipeCoordinatorTest {
    private static final Runnable NO_OP = () -> {};

    @Test void realEnergyStagesCompleteAcrossHostsWhileZeroSpeedStillPaysExactlyOneTick() {
        var energy = energy(NO_OP);
        var hosts = List.of(host(), host(), host());
        var events = new ObjectArrayList<String>();
        var phases = new ObjectArrayList<String>();
        Thread owner = Thread.currentThread();
        var processes = new ObjectArrayList<RecipeProcess>();
        for (int index = 0; index < hosts.size(); index++) {
            var current = hosts.get(index);
            current.owner().components().put(MachineHatches.TYPE,
                new MachineHatches(List.of(), List.of(energy), List.of(energy)));
            String name = "p" + index;
            var io = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 1, false));
            var observer = requirement((_, phase) -> {
                assertSame(owner, Thread.currentThread());
                phases.add(name + ":" + phase);
                return transaction(ProcessResult.SUCCESS, List.of(), () -> assertSame(owner, Thread.currentThread()), NO_OP);
            });
            var process = process(current, name, 1, io, observer);
            callbacks(process, name, events, NO_OP, NO_OP);
            processes.add(process);
        }
        processes.getLast().attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0);
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, hosts);
            assertTrue(events.isEmpty(), "Capture must not run pre callbacks");
            for (var current : hosts) { assertThrows(IllegalStateException.class, current::snapshot); }
            assertTrue(coordinator.advance(deadline()));
            assertEquals(List.of("p0:START", "p1:START", "p2:START", "p0:TICK", "p1:TICK", "p2:TICK", "p0:END", "p1:END"), phases);
            assertEquals(87, energy.savedEnergy());
            assertEquals(List.of("pre:p0", "tick:p0", "pre:p1", "tick:p1", "pre:p2", "tick:p2", "post:p0", "post:p1", "post:p2"), events);
            assertNull(processes.getFirst().host());
            assertNull(processes.get(1).host());
            assertSame(hosts.getLast(), processes.getLast().host());
            assertEquals(0, processes.getLast().state().progress());
            assertEquals(1, processes.getLast().state().successfulTicks());
            assertEquals(RecipeProcess.Lifecycle.RUNNING, processes.getLast().state().lifecycle());
            assertTrue(coordinator.advance(deadline()));
            assertEquals(87, energy.savedEnergy(), "A finished coordinator must not replay a tick");
            for (var current : hosts) { current.snapshot(); }
        }
    }

    @Test void rejectedAcquisitionReleasesItsHoldBeforeAnotherHostAcquires() {
        var energy = energy(NO_OP);
        var blockedHost = host();
        var successfulHost = host();
        var blockedResult = new ProcessResult.Blocked("waiting");
        var events = new ObjectArrayList<String>();
        var blocked = process(blockedHost, "blocked", 0, requirement((_, _) -> {
            events.add("blocked acquired");
            return transaction(blockedResult, List.of(reserve(energy, 75)),
                () -> fail("Rejected business committed"), () -> events.add("blocked rolled back"));
        }));
        var successful = process(successfulHost, "successful", 0, requirement((_, phase) -> {
            if (phase != RecipeStage.START) { return RequirementTransaction.SUCCESS; }
            events.add("successful acquired");
            assertEquals(100, energy.energySnapshot().availableEnergy());
            return transaction(ProcessResult.SUCCESS, List.of(reserve(energy, 80)), NO_OP, NO_OP);
        }));
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, List.of(blockedHost, successfulHost));
            assertTrue(coordinator.advance(deadline()));
            assertEquals(List.of("blocked acquired", "blocked rolled back", "successful acquired"), events);
            assertEquals(20, energy.savedEnergy());
            assertSame(blockedHost, blocked.host());
            assertSame(blockedResult, blocked.state().result());
            assertEquals(RecipeProcess.Lifecycle.NEW, blocked.state().lifecycle());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, successful.state().lifecycle());
            assertNull(successful.host());
            assertNull(coordinator.stopReason());
        }
    }

    @Test void laterBusinessCommitFailureCompensatesEveryAcquiredHostInReverseOrder() {
        var energy = energy(NO_OP);
        var hosts = List.of(host(), host(), host());
        var log = new ObjectArrayList<String>();
        var processes = new ObjectArrayList<RecipeProcess>();
        var failure = new IllegalArgumentException("second business commit");
        var effects = new int[3];
        for (int index = 0; index < hosts.size(); index++) {
            int current = index;
            processes.add(process(hosts.get(index), "p" + index, 1, requirement((_, _) -> {
                log.add("acquire:" + current);
                effects[current]++;
                return transaction(ProcessResult.SUCCESS, List.of(reserve(energy, 10)), () -> {
                    log.add("commit:" + current);
                    effects[current]++;
                    if (current == 1) { throw failure; }
                }, () -> { log.add("rollback:" + current); effects[current] = 0; });
            })));
        }
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, hosts);
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> coordinator.advance(deadline())));
            assertTrue(coordinator.finished());
            assertEquals(List.of("acquire:0", "acquire:1", "acquire:2", "commit:0", "commit:1", "rollback:2", "rollback:1", "rollback:0"), log);
            assertArrayEquals(new int[3], effects);
            assertEquals(100, energy.savedEnergy());
            assertEquals(100, energy.energySnapshot().availableEnergy());
            for (var process : processes) {
                assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
                assertEquals(0, process.state().successfulTicks());
            }
            assertTrue(coordinator.advance(deadline()));
            assertEquals(8, log.size(), "Cleanup and commit cannot replay after finish");
        }
    }

    @Test void partialAcquisitionFailureRollsBackEarlierRequirementsAndSkipsUntouchedHosts() {
        var energy = energy(NO_OP);
        var firstHost = host();
        var untouchedHost = host();
        var log = new ObjectArrayList<String>();
        var failure = new AssertionError("partial requirement acquisition");
        var first = process(firstHost, "first", 1,
            requirement("held", (_, _) -> transaction(ProcessResult.SUCCESS, List.of(reserve(energy, 30)),
                () -> fail("Partial acquisition committed"), () -> log.add("rollback held"))),
            requirement("throws", (_, _) -> { throw failure; }));
        process(untouchedHost, "untouched", 1, requirement((_, _) -> {
            fail("Later host must never acquire after a partial acquisition fault");
            return RequirementTransaction.SUCCESS;
        }));
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, List.of(firstHost, untouchedHost));
            assertSame(failure, assertThrows(AssertionError.class, () -> coordinator.advance(deadline())));
            assertTrue(coordinator.finished());
            assertEquals(List.of("rollback held"), log);
            assertEquals(100, energy.savedEnergy());
            assertEquals(100, energy.energySnapshot().availableEnergy());
            assertEquals(RecipeProcess.Lifecycle.NEW, first.state().lifecycle());
            assertNull(firstHost.pendingTickBatch());
            assertNull(untouchedHost.pendingTickBatch());
        }
    }

    @Test void laterPreFailureFinishesEarlierCallbacksAndNeverEntersUntouchedHost() {
        var hosts = List.of(host(), host(), host());
        var log = new ObjectArrayList<String>();
        var failure = new IllegalStateException("later pre failed");
        for (int index = 0; index < hosts.size(); index++) {
            int current = index;
            var process = process(hosts.get(index), "p" + index, 1);
            callbacks(process, "p" + index, log, () -> { if (current == 1) { throw failure; } }, NO_OP);
        }
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, hosts);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())));
            assertTrue(coordinator.finished());
            assertEquals(List.of("pre:p0", "tick:p0", "pre:p1", "post:p0"), log);
            assertTrue(coordinator.advance(deadline()));
            assertEquals(4, log.size());
            for (var current : hosts) { assertNull(current.pendingTickBatch()); }
        }
    }

    @Test void unknownSourceRetainsEveryHostUntilOriginalLaneCanConfirmAndComplete() {
        var energy = energy(NO_OP);
        var hosts = List.of(host(), host());
        var recoverable = new AtomicBoolean();
        var completes = new AtomicInteger();
        var rollsBack = new AtomicInteger();
        var failure = new IllegalStateException("unknown source completion");
        var sourceThread = new Thread[1];
        var log = new ObjectArrayList<String>();
        process(hosts.getFirst(), "first", 1, requirement((_, _) -> {
            var token = reserve(energy, 10);
            return transaction(ProcessResult.SUCCESS, List.of(new DelegatingReservation(token) {
                @Override public void apply() { sourceThread[0] = Thread.currentThread(); super.apply(); }
                @Override public void complete() {
                    assertSame(sourceThread[0], Thread.currentThread());
                    completes.incrementAndGet();
                    if (!recoverable.get()) { throw failure; }
                    super.complete();
                }
                @Override public void rollback() { rollsBack.incrementAndGet(); super.rollback(); }
                @Override public Resolution resolution() { return recoverable.get() ? super.resolution() : Resolution.UNKNOWN; }
            }), NO_OP, NO_OP);
        }));
        process(hosts.getLast(), "second", 1);
        for (var current : hosts) { callbacks(current.activeProcesses().getFirst(), "p", log, NO_OP, NO_OP); }
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, hosts);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())));
            assertFalse(coordinator.finished());
            assertFalse(coordinator.advance(deadline()));
            assertEquals(1, completes.get(), "UNKNOWN cannot authorize another completion attempt");
            assertEquals(0, rollsBack.get(), "COMPLETE cannot turn into compensation");
            assertEquals(0, log.stream().filter(event -> event.startsWith("post:")).count());
            for (var current : hosts) { assertThrows(IllegalStateException.class, current::snapshot); }
            recoverable.set(true);
            assertTrue(coordinator.advance(deadline()));
            assertEquals(2, completes.get());
            assertEquals(0, rollsBack.get());
            assertEquals(90, energy.savedEnergy());
            assertEquals(2, log.stream().filter(event -> event.startsWith("post:")).count());
            assertNotSame(Thread.currentThread(), sourceThread[0]);
        }
    }

    @Test void unknownBusinessCompletionWaitsForExactProofWithoutReplayingTheMutation() {
        var current = host();
        var proof = new AtomicBoolean();
        var completions = new AtomicInteger();
        var commits = new AtomicInteger();
        var rollback = new AtomicInteger();
        var retryAttempts = new AtomicInteger();
        var retriedCompletion = new AtomicBoolean();
        var failure = new IllegalStateException("business completion after mutation");
        var process = process(current, "work", 1, requirement("terminal", (_, _) -> new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public void commit() { commits.incrementAndGet(); }
            @Override public void complete() { completions.incrementAndGet(); throw failure; }
            @Override public void rollback() { rollback.incrementAndGet(); }
            @Override public ReversibleTransaction.Resolution resolution() {
                return proof.get() ? ReversibleTransaction.Resolution.COMPLETED : ReversibleTransaction.Resolution.UNKNOWN;
            }
        }), requirement("retry", (_, _) -> new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public void commit() { }
            @Override public void complete() {
                retryAttempts.incrementAndGet();
                if (!proof.get()) { throw failure; }
                retriedCompletion.set(true);
            }
            @Override public void rollback() { rollback.incrementAndGet(); }
            @Override public ReversibleTransaction.Resolution resolution() {
                if (retriedCompletion.get()) { return ReversibleTransaction.Resolution.COMPLETED; }
                return proof.get() ? ReversibleTransaction.Resolution.REVERSIBLE : ReversibleTransaction.Resolution.UNKNOWN;
            }
        }));
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, List.of(current));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())));
            assertFalse(coordinator.advance(deadline()));
            assertThrows(IllegalStateException.class, current::snapshot);
            assertEquals(1, commits.get());
            assertEquals(1, completions.get());
            proof.set(true);
            var expired = new PlannedDeadline(System.nanoTime() - TimeUnit.SECONDS.toNanos(1), 1);
            assertFalse(coordinator.advance(expired));
            assertEquals(1, completions.get());
            assertEquals(1, retryAttempts.get(), "Proven-safe cleanup still needs an unexpired cleanup budget");
            assertEquals(0, rollback.get());
            assertFalse(retriedCompletion.get());
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, commits.get());
            assertEquals(1, completions.get());
            assertEquals(0, rollback.get());
            assertEquals(2, retryAttempts.get());
            assertTrue(retriedCompletion.get());
            assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
            assertEquals(0, process.state().successfulTicks(), "Completion fault stops subsequent forward rounds");
        }
    }

    @Test void cancelledInflightSourceRemainsPinnedUntilActualExitThenCompensatesOnCleanup() throws Exception {
        var current = host();
        var energy = energy(NO_OP);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var cancellation = new PlannedCancellationToken();
        var applies = new AtomicInteger();
        var rollback = new AtomicInteger();
        var businessCommits = new AtomicInteger();
        var log = new ObjectArrayList<String>();
        var process = process(current, "inflight", 1, requirement((_, _) -> {
            var token = reserve(energy, 20);
            return transaction(ProcessResult.SUCCESS, List.of(new DelegatingReservation(token) {
                @Override public void apply() {
                    applies.incrementAndGet();
                    super.apply();
                    entered.countDown();
                    awaitIgnoringInterrupt(release, interrupted);
                }
                @Override public void rollback() { rollback.incrementAndGet(); super.rollback(); }
            }), businessCommits::incrementAndGet, NO_OP);
        }));
        callbacks(process, "work", log, NO_OP, NO_OP);
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(current), deadline(), cancellation, () -> true);
            Thread canceller = Thread.ofPlatform().daemon().start(() -> {
                await(entered);
                cancellation.cancel(Reason.MACHINE_UNLOAD);
                cancelled.countDown();
            });
            try {
                assertFalse(coordinator.advance(deadline()));
                await(cancelled);
                await(interrupted);
                assertFalse(coordinator.finished());
                assertFalse(coordinator.advance(deadline()));
                assertThrows(IllegalStateException.class, current::snapshot);
                assertEquals(List.of("pre:work", "tick:work"), log);
                release.countDown();
                coordinator.awaitSourceExit();
                assertTrue(coordinator.advance(deadline()));
                assertEquals(Reason.MACHINE_UNLOAD, coordinator.stopReason());
                assertEquals(1, applies.get());
                assertEquals(1, rollback.get());
                assertEquals(0, businessCommits.get());
                assertEquals(100, energy.savedEnergy());
                assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
                assertEquals(List.of("pre:work", "tick:work", "post:work"), log);
                current.snapshot();
            } finally {
                release.countDown();
                canceller.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(canceller.isAlive());
            }
        } finally { release.countDown(); }
    }

    @Test void releaseNotificationFailureDoesNotReplayCompletedSourcesOrAdvanceLaterStages() {
        var armed = new AtomicBoolean();
        var notifications = new AtomicInteger();
        var completions = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        var failure = new IllegalStateException("released storage notification");
        var energy = energy(() -> { if (armed.get()) { notifications.incrementAndGet(); throw failure; } });
        var current = host();
        var process = process(current, "notification", 1, requirement((_, _) -> {
            var token = reserve(energy, 10);
            return transaction(ProcessResult.SUCCESS, List.of(new DelegatingReservation(token) {
                @Override public void apply() { super.apply(); armed.set(true); }
                @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                @Override public void rollback() { rollbacks.incrementAndGet(); super.rollback(); }
            }), NO_OP, NO_OP);
        }));
        try (var backend = new Backend()) {
            var coordinator = coordinator(backend, List.of(current));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())));
            assertTrue(coordinator.finished());
            armed.set(false);
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, notifications.get());
            assertEquals(1, completions.get());
            assertEquals(0, rollbacks.get());
            assertEquals(90, energy.savedEnergy());
            assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
            assertEquals(0, process.state().successfulTicks());
            assertNull(current.pendingTickBatch());
        } finally { armed.set(false); }
    }

    @Test void callbacksCannotEnterForeignHostsOrReenterTheCoordinatorWhileLeasesRemainCaptured() {
        var firstHost = host();
        var secondHost = host();
        var second = process(secondHost, "second", 0);
        PlannedRecipeCoordinator[] captured = {null};
        var checked = new AtomicInteger();
        Runnable rejectReentry = () -> {
            assertThrows(IllegalStateException.class, second::tick);
            assertThrows(IllegalStateException.class, secondHost::snapshot);
            assertThrows(IllegalStateException.class, secondHost::tickProcesses);
            assertThrows(IllegalStateException.class, () -> captured[0].advance(deadline()));
            checked.incrementAndGet();
        };
        var first = process(firstHost, "first", 0, requirement((_, _) -> {
            rejectReentry.run();
            return transaction(ProcessResult.SUCCESS, List.of(), rejectReentry, NO_OP);
        }));
        var log = new ObjectArrayList<String>();
        callbacks(first, "first", log, rejectReentry, rejectReentry);
        try (var backend = new Backend()) {
            captured[0] = coordinator(backend, List.of(firstHost, secondHost));
            assertTrue(captured[0].advance(deadline()));
            assertEquals(6, checked.get());
            assertEquals(List.of("pre:first", "tick:first", "post:first"), log);
            firstHost.snapshot();
            secondHost.snapshot();
        }
    }

    @Test void captureRejectsLaterCustomExecutorWithoutLeavingAnyEarlierLeaseOrPreCallback() {
        var first = host();
        var log = new ObjectArrayList<String>();
        callbacks(process(first, "first", 1), "first", log, NO_OP, NO_OP);
        var custom = new MachineWorkSlots(new MachineInstance(), List.of(), Set.of(), RecipeProcess::tick);
        try (var backend = new Backend()) {
            assertThrows(IllegalStateException.class, () -> coordinator(backend, List.of(first, custom)));
            assertTrue(log.isEmpty());
            assertNull(first.pendingTickBatch());
            assertNull(custom.pendingTickBatch());
            first.snapshot();
            first.tickProcesses();
            assertEquals(List.of("pre:first", "tick:first", "post:first"), log);
        }
    }

    private static PlannedRecipeCoordinator coordinator(Backend backend, List<MachineWorkSlots> hosts) {
        return new PlannedRecipeCoordinator(backend.tasks, hosts, deadline(), new PlannedCancellationToken(), () -> true);
    }

    private static PlannedDeadline deadline() { return PlannedDeadline.after(Duration.ofSeconds(5)); }
    private static MachineWorkSlots host() { return new MachineWorkSlots(new MachineInstance(), List.of()); }
    private static EnergyBuffer energy(Runnable notification) {
        var buffer = new EnergyBuffer(100, notification);
        assertEquals(100, buffer.receiveEnergy(100, Actionable.MODULATE));
        return buffer;
    }
    private static ResourceReservation reserve(EnergyBuffer energy, long amount) {
        return Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), amount).reservation());
    }
    private static RecipeProcess process(MachineWorkSlots host, String name, int duration, RecipeRequirement<?>... requirements) {
        var process = new RecipeProcess(host.owner(), new MachineRecipe(id(name), duration, List.of(requirements)), 1);
        assertTrue(host.start(process));
        return process;
    }
    private static RequirementTransaction transaction(ProcessResult result, List<ResourceReservation> reservations,
                                                       Runnable commit, Runnable rollback) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return result; }
            @Override public List<ResourceReservation> reservations() { return reservations; }
            @Override public void commit() { commit.run(); }
            @Override public void rollback() { rollback.run(); }
        };
    }
    private static RecipeRequirement<String> requirement(BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        return requirement("observer", action);
    }
    private static RecipeRequirement<String> requirement(String name, BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.START); }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.TICK); }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.END); }
        };
        return new RecipeRequirementType<>(id(name), system).requirement(id(name), "value");
    }
    private static void callbacks(RecipeProcess process, String name, List<String> log, Runnable pre, Runnable post) {
        process.components().create(new ComponentType<>(id("component"), _ -> "value", List.of(new ComponentSystem<RecipeProcess, String>() {
            @Override public ResourceLocation id() { return PlannedRecipeCoordinatorTest.id("system"); }
            @Override public void onPreTick(RecipeProcess owner, String component) { log.add("pre:" + name); pre.run(); }
            @Override public void onTick(RecipeProcess owner, String component) { log.add("tick:" + name); }
            @Override public void onPostTick(RecipeProcess owner, String component) { log.add("post:" + name); post.run(); }
        })), process);
    }
    private static ResourceLocation id(String name) { return new ResourceLocation("planned_recipe_coordinator_test", name); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Test coordination must complete"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch, CountDownLatch interrupted) {
        boolean done = false;
        while (!done) {
            try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Test must release source action"); done = true; }
            catch (InterruptedException failure) { interrupted.countDown(); }
        }
    }
    private static class DelegatingReservation implements ResourceReservation {
        private final ResourceReservation delegate;
        private DelegatingReservation(ResourceReservation delegate) { this.delegate = delegate; }
        @Override public StorageContext context() { return delegate.context(); }
        @Override public boolean canApply() { return delegate.canApply(); }
        @Override public void apply() { delegate.apply(); }
        @Override public void complete() { delegate.complete(); }
        @Override public void rollback() { delegate.rollback(); }
        @Override public Resolution resolution() { return delegate.resolution(); }
    }
    private static final class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        private final ExecutorService lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});
        @Override public void close() {
            workers.shutdownNow();
            lane.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(lane.awaitTermination(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
}
