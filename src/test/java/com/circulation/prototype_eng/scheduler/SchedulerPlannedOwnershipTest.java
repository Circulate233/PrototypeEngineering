package com.circulation.prototype_eng.scheduler;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.MachineInstance;
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
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SchedulerPlannedOwnershipTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static void enqueueBoundary(TaskScheduler scheduler, Runnable action) throws Exception {
        var queued = new FutureTask<Void>(() -> {
            scheduler.runAtBoundary(action);
            return null;
        });
        Thread.ofPlatform().daemon().start(queued);
        queued.get(5, TimeUnit.SECONDS);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("scheduler_planned_ownership_test", path);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Test coordination must complete");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch, CountDownLatch interrupted) {
        boolean done = false;
        while (!done) {
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS), "Test must release its source action");
                done = true;
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    @Test
    void stoppedSourceMustActuallyExitBeforeTickReturnsAndPendingCycleBlocksBusinessUntilCleanupFinishes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var awaitEntered = new CountDownLatch(1);
        var tickReturned = new AtomicBoolean();
        var boundaryCalls = new AtomicInteger();
        var ordinaryCalls = new AtomicInteger();
        var applies = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        var captures = new AtomicInteger();
        var postReady = new AtomicBoolean();
        var token = new PlannedCancellationToken();
        var fixture = new RecipeFixture(actual -> new ReservationAdapter(actual) {
            @Override
            public void apply() {
                super.apply();
                applies.incrementAndGet();
                entered.countDown();
                awaitIgnoringInterrupt(release, interrupted);
            }

            @Override
            public void rollback() {
                rollbacks.incrementAndGet();
                super.rollback();
            }
        });
        var cycle = new AtomicReference<RealCycle>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((tasks, deadline) -> {
                if (captures.incrementAndGet() != 1) {
                    return null;
                }
                var captured = new RealCycle(tasks, deadline, fixture.slots, token, postReady::get, awaitEntered);
                cycle.set(captured);
                return captured;
            });
            var stopAndRelease = new FutureTask<Void>(() -> {
                await(entered);
                scheduler.runAtBoundary(boundaryCalls::incrementAndGet);
                token.cancel(Reason.EXPLICIT_CANCEL);
                await(interrupted);
                await(awaitEntered);
                assertFalse(tickReturned.get(), "Stopped results cannot release a scheduler tick before source exit");
                assertEquals(0, boundaryCalls.get());
                assertFalse(backend.lane.isShutdown());
                release.countDown();
                return null;
            });
            Thread.ofPlatform().daemon().start(stopAndRelease);
            try {
                scheduler.tick();
                tickReturned.set(true);
                stopAndRelease.get(5, TimeUnit.SECONDS);
                assertEquals(Reason.EPOCH_CHANGED, cycle.get().lastCancellation.get());
                assertTrue(Objects.requireNonNull(cycle.get().coordinator).finished());
                assertFalse(cycle.get().finished());
                assertEquals(0, boundaryCalls.get());
                assertEquals(100, fixture.energy.savedEnergy());
                scheduler.submit(ordinaryCalls::incrementAndGet, ExecutionMode.MAIN_THREAD);
                scheduler.tick();
                assertFalse(cycle.get().finished());
                assertEquals(1, captures.get(), "Pending cleanup must not recapture new business before the whole cycle finishes");
                assertEquals(0, ordinaryCalls.get());
                assertEquals(0, boundaryCalls.get());
                assertEquals(1, applies.get());
                assertEquals(1, rollbacks.get());
                assertEquals(100, fixture.energy.savedEnergy());
                assertEquals(RecipeProcess.Lifecycle.NEW, fixture.process.state().lifecycle());
                postReady.set(true);
                scheduler.tick();
                assertTrue(cycle.get().finished());
                assertEquals(1, boundaryCalls.get());
                assertEquals(1, ordinaryCalls.get(), "Queued ordinary work must survive normal pending recovery");
                assertEquals(2, captures.get());
                assertTrue(scheduler.tryClose());
            } finally {
                release.countDown();
                stopAndRelease.get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void completedRecipeDoesNotReleaseBoundaryWhileTheWholeCycleStillOwnsPendingPost() throws Exception {
        var fixture = new RecipeFixture();
        var postReady = new AtomicBoolean();
        var boundaries = new AtomicInteger();
        var ordinary = new AtomicInteger();
        var captures = new AtomicInteger();
        var cycle = new AtomicReference<RealCycle>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((tasks, deadline) -> {
                if (captures.incrementAndGet() != 1) {
                    return null;
                }
                var captured = new RealCycle(tasks, deadline, fixture.slots, new PlannedCancellationToken(), postReady::get, new CountDownLatch(0));
                cycle.set(captured);
                return captured;
            });
            scheduler.tick();
            assertTrue(Objects.requireNonNull(cycle.get().coordinator).finished());
            assertFalse(cycle.get().finished());
            assertEquals(90, fixture.energy.savedEnergy());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, fixture.process.state().lifecycle());
            enqueueBoundary(scheduler, () -> {
                assertTrue(cycle.get().finished(), "The whole cycle must release before an owner boundary runs");
                boundaries.incrementAndGet();
            });
            scheduler.submit(ordinary::incrementAndGet, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(0, ordinary.get());
            assertEquals(0, boundaries.get());
            assertEquals(1, captures.get());
            assertFalse(scheduler.tryClose());
            assertEquals(0, boundaries.get());
            assertFalse(backend.lane.isShutdown(), "Post pending must preserve original source lanes for cleanup");
            postReady.set(true);
            scheduler.tick();
            assertTrue(cycle.get().finished());
            assertEquals(0, boundaries.get(), "A close request still requires executor termination after cycle cleanup");
            assertFalse(backend.lane.isShutdown());
            assertEquals(0, ordinary.get());
            assertTrue(scheduler.tryClose());
            assertEquals(1, boundaries.get());
            assertEquals(0, ordinary.get(), "Close may revoke queued business but must still drain accepted boundaries");
            assertTrue(backend.lane.isShutdown());
            assertEquals(90, fixture.energy.savedEnergy());
        }
    }

    @Test
    void unknownSourceCompletionKeepsCloseRetryableAndOriginalLaneAliveUntilExactProof() throws Exception {
        var proof = new AtomicBoolean();
        var completionCalls = new AtomicInteger();
        var rollbackCalls = new AtomicInteger();
        var boundaries = new AtomicInteger();
        var sourceThread = new AtomicReference<Thread>();
        var failure = new IllegalStateException("source completed but proof withheld");
        var fixture = new RecipeFixture(actual -> new ReservationAdapter(actual) {
            @Override
            public void apply() {
                sourceThread.set(Thread.currentThread());
                super.apply();
            }

            @Override
            public void complete() {
                assertSame(sourceThread.get(), Thread.currentThread());
                completionCalls.incrementAndGet();
                super.complete();
                throw failure;
            }

            @Override
            public void rollback() {
                rollbackCalls.incrementAndGet();
                super.rollback();
            }

            @Override
            public Resolution resolution() {
                assertSame(sourceThread.get(), Thread.currentThread());
                return proof.get() ? super.resolution() : Resolution.UNKNOWN;
            }
        });
        var captured = new AtomicReference<RealCycle>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((tasks, deadline) -> {
                var cycle = new RealCycle(tasks, deadline, fixture.slots, new PlannedCancellationToken(), () -> true, new CountDownLatch(0));
                captured.set(cycle);
                return cycle;
            });
            assertSame(failure, assertThrows(IllegalStateException.class, scheduler::tick));
            enqueueBoundary(scheduler, () -> {
                assertTrue(captured.get().finished());
                assertEquals(90, fixture.energy.savedEnergy());
                boundaries.incrementAndGet();
            });
            assertFalse(scheduler.tryClose());
            assertThrows(IllegalStateException.class, scheduler::close);
            assertFalse(backend.workers.isShutdown());
            assertFalse(backend.lane.isShutdown());
            assertEquals(0, boundaries.get());
            assertEquals(1, completionCalls.get(), "UNKNOWN cannot authorize another terminal mutation");
            assertEquals(0, rollbackCalls.get(), "An irrevocable COMPLETE must never turn into a rollback");
            assertThrows(IllegalStateException.class, fixture.slots::snapshot);
            proof.set(true);
            assertTrue(scheduler.tryClose());
            assertEquals(1, boundaries.get());
            assertEquals(1, completionCalls.get());
            assertEquals(0, rollbackCalls.get());
            assertTrue(backend.workers.isTerminated());
            assertTrue(backend.lane.isTerminated());
            assertEquals(90, fixture.energy.savedEnergy());
            assertTrue(scheduler.tryClose());
            assertEquals(1, boundaries.get());
        }
    }

    @Test
    void plannedCaptureCannotReenterSchedulerAndSubmissionsWaitForTheNextBusinessTick() {
        var fixture = new RecipeFixture();
        var captures = new AtomicInteger();
        var queued = new AtomicInteger();
        var illegalBodies = new AtomicInteger();
        TaskScheduler[] scheduler = {null};
        try (var backend = new Backend()) {
            scheduler[0] = backend.scheduler((tasks, deadline) -> {
                assertThrows(IllegalStateException.class, scheduler[0]::tick);
                assertThrows(IllegalStateException.class, scheduler[0]::tryClose);
                assertThrows(IllegalStateException.class, () -> scheduler[0].runAtBoundary(illegalBodies::incrementAndGet));
                if (captures.incrementAndGet() != 1) {
                    return null;
                }
                scheduler[0].submit(queued::incrementAndGet, ExecutionMode.MAIN_THREAD);
                return new ExecutionAdapter(new RealCycle(tasks, deadline, fixture.slots, new PlannedCancellationToken(), () -> true, new CountDownLatch(0))) {
                    @Override
                    public boolean finished() {
                        assertThrows(IllegalStateException.class, () -> scheduler[0].runAtBoundary(illegalBodies::incrementAndGet));
                        return super.finished();
                    }
                };
            });
            scheduler[0].tick();
            assertEquals(0, queued.get());
            assertEquals(0, illegalBodies.get());
            assertEquals(90, fixture.energy.savedEnergy());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, fixture.process.state().lifecycle());
            scheduler[0].tick();
            assertEquals(1, queued.get());
            assertEquals(2, captures.get());
            assertEquals(90, fixture.energy.savedEnergy());
            assertTrue(scheduler[0].tryClose());
        }
    }

    @Test
    void failedCancellationCannotAuthorizeAnotherAdvanceUntilTheExactCycleConfirmsItsStop() throws Exception {
        var fixture = new RecipeFixture();
        var cancellation = new PlannedCancellationToken();
        var postReady = new AtomicBoolean();
        var advances = new AtomicInteger();
        var cancels = new AtomicInteger();
        var captures = new AtomicInteger();
        var boundaries = new AtomicInteger();
        var failure = new IllegalStateException("cancellation failed before revoking the token");
        var cycle = new AtomicReference<RealCycle>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((tasks, deadline) -> {
                captures.incrementAndGet();
                var real = new RealCycle(tasks, deadline, fixture.slots, cancellation, postReady::get, new CountDownLatch(0));
                cycle.set(real);
                return new ExecutionAdapter(real) {
                    @Override
                    public boolean advance(PlannedDeadline cleanupDeadline) {
                        if (advances.incrementAndGet() > 1) {
                            assertNotNull(cancellation.reason(), "An unconfirmed cancel must not authorize another advance");
                        }
                        return super.advance(cleanupDeadline);
                    }

                    @Override
                    public void cancel(Reason reason) {
                        if (cancels.incrementAndGet() == 1) {
                            throw failure;
                        }
                        super.cancel(reason);
                    }
                };
            });
            assertSame(failure, assertThrows(IllegalStateException.class, scheduler::tick));
            assertEquals(1, advances.get());
            assertEquals(1, cancels.get());
            assertNull(cancellation.reason());
            assertFalse(cycle.get().finished());
            assertEquals(90, fixture.energy.savedEnergy());
            enqueueBoundary(scheduler, () -> {
                assertTrue(cycle.get().finished());
                boundaries.incrementAndGet();
            });
            assertEquals(0, boundaries.get());
            scheduler.tick();
            assertEquals(2, cancels.get());
            assertEquals(2, advances.get());
            assertEquals(1, captures.get(), "Recovery must retain the captured cycle after cancellation failure");
            assertFalse(cycle.get().finished());
            assertEquals(0, boundaries.get());
            assertFalse(backend.lane.isShutdown());
            assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> fail("Faulted business must never run"), ExecutionMode.MAIN_THREAD));
            postReady.set(true);
            assertTrue(scheduler.tryClose());
            assertEquals(3, advances.get());
            assertEquals(2, cancels.get());
            assertEquals(1, captures.get());
            assertEquals(1, boundaries.get());
            assertEquals(90, fixture.energy.savedEnergy(), "Recovery must not replay the completed FE input");
            assertTrue(backend.lane.isTerminated());
        }
    }

    @Test
    void interruptedOwnerWaitsForActualSourceExitAndRetainsItsCleanupBeforeReportingTheFailure() throws Exception {
        var owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sourceInterrupted = new CountDownLatch(1);
        var awaitEntered = new CountDownLatch(1);
        var awaitEnded = new CountDownLatch(1);
        var sourceExited = new AtomicBoolean();
        var tickReported = new AtomicBoolean();
        var postReady = new AtomicBoolean();
        var boundaries = new AtomicInteger();
        var applies = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        var cancellation = new PlannedCancellationToken();
        var fixture = new RecipeFixture(actual -> new ReservationAdapter(actual) {
            @Override
            public void apply() {
                super.apply();
                applies.incrementAndGet();
                entered.countDown();
                try {
                    awaitIgnoringInterrupt(release, sourceInterrupted);
                } finally {
                    sourceExited.set(true);
                }
            }

            @Override
            public void rollback() {
                rollbacks.incrementAndGet();
                super.rollback();
            }
        });
        var cycle = new AtomicReference<RealCycle>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((tasks, deadline) -> {
                var real = new RealCycle(tasks, deadline, fixture.slots, cancellation, postReady::get, awaitEntered);
                cycle.set(real);
                return new ExecutionAdapter(real) {
                    @Override
                    public void awaitExit() {
                        try {
                            super.awaitExit();
                        } finally {
                            awaitEnded.countDown();
                            assertTrue(sourceExited.get(), "Exit confirmation or its interruption must follow actual source exit");
                        }
                    }
                };
            });
            var interruptAndRelease = new FutureTask<Void>(() -> {
                await(entered);
                scheduler.runAtBoundary(() -> {
                    assertTrue(sourceExited.get());
                    assertTrue(cycle.get().finished());
                    boundaries.incrementAndGet();
                });
                cancellation.cancel(Reason.EXPLICIT_CANCEL);
                await(sourceInterrupted);
                await(awaitEntered);
                owner.interrupt();
                assertFalse(awaitEnded.await(100, TimeUnit.MILLISECONDS), "Owner interruption cannot finish an await while the real source still runs");
                assertFalse(tickReported.get());
                assertFalse(sourceExited.get());
                assertEquals(0, boundaries.get());
                assertFalse(backend.lane.isShutdown());
                release.countDown();
                return null;
            });
            Thread.ofPlatform().daemon().start(interruptAndRelease);
            try {
                var failure = assertThrows(IllegalStateException.class, scheduler::tick);
                tickReported.set(true);
                assertInstanceOf(InterruptedException.class, failure.getCause());
                assertTrue(owner.isInterrupted(), "The scheduler must restore owner interruption after actual exit");
                assertTrue(Thread.interrupted());
                interruptAndRelease.get(5, TimeUnit.SECONDS);
                assertTrue(sourceExited.get());
                assertTrue(Objects.requireNonNull(cycle.get().coordinator).finished());
                assertFalse(cycle.get().finished(), "The exact post obligation must survive the reported interruption");
                assertEquals(1, applies.get());
                assertEquals(1, rollbacks.get());
                assertEquals(100, fixture.energy.savedEnergy());
                assertEquals(RecipeProcess.Lifecycle.NEW, fixture.process.state().lifecycle());
                assertEquals(0, boundaries.get());
                assertFalse(backend.lane.isShutdown());
                assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> fail("Faulted business must never run"), ExecutionMode.MAIN_THREAD));
                postReady.set(true);
                assertTrue(scheduler.tryClose());
                assertEquals(1, boundaries.get());
                assertEquals(1, applies.get());
                assertEquals(1, rollbacks.get());
                assertEquals(100, fixture.energy.savedEnergy());
            } finally {
                //noinspection ResultOfMethodCallIgnored -- Failure-path cleanup must not leak this test's interrupt into JUnit.
                Thread.interrupted();
                release.countDown();
                interruptAndRelease.get(5, TimeUnit.SECONDS);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored -- Executor cleanup can fail after the owner interrupt was restored.
            Thread.interrupted();
            release.countDown();
        }
    }

    private static final class RealCycle implements PlannedExecution {
        private final PlannedTaskExecutor tasks;
        private final PlannedDeadline forwardDeadline;
        private final MachineWorkSlots slots;
        private final PlannedCancellationToken cancellation;
        private final BooleanSupplier postReady;
        private final CountDownLatch awaitEntered;
        private final AtomicReference<Reason> lastCancellation = new AtomicReference<>();
        private PlannedRecipeCoordinator coordinator;
        private boolean postFinished;

        private RealCycle(PlannedTaskExecutor tasks, PlannedDeadline deadline, MachineWorkSlots slots,
                          PlannedCancellationToken cancellation, BooleanSupplier postReady, CountDownLatch awaitEntered) {
            this.tasks = tasks;
            this.forwardDeadline = deadline;
            this.slots = slots;
            this.cancellation = cancellation;
            this.postReady = postReady;
            this.awaitEntered = awaitEntered;
        }

        @Override
        public boolean advance(PlannedDeadline cleanupDeadline) {
            if (coordinator == null) {
                coordinator = new PlannedRecipeCoordinator(tasks, List.of(slots), forwardDeadline, cancellation, () -> true);
            }
            if (!coordinator.advance(cleanupDeadline)) {
                return false;
            }
            if (!postReady.getAsBoolean()) {
                return false;
            }
            postFinished = true;
            return true;
        }

        @Override
        public void awaitExit() {
            awaitEntered.countDown();
            if (coordinator != null) {
                coordinator.awaitExit();
            }
        }

        @Override
        public boolean finished() {
            return coordinator != null && coordinator.finished() && postFinished;
        }

        @Override
        public void cancel(Reason reason) {
            lastCancellation.set(reason);
            cancellation.cancel(reason);
        }
    }

    private static class ExecutionAdapter implements PlannedExecution {
        private final PlannedExecution actual;

        private ExecutionAdapter(PlannedExecution actual) {
            this.actual = actual;
        }

        @Override
        public boolean advance(PlannedDeadline cleanupDeadline) {
            return actual.advance(cleanupDeadline);
        }

        @Override
        public void awaitExit() {
            actual.awaitExit();
        }

        @Override
        public boolean finished() {
            return actual.finished();
        }

        @Override
        public void cancel(Reason reason) {
            actual.cancel(reason);
        }
    }

    private static final class RecipeFixture {
        private final MachineInstance machine = new MachineInstance();
        private final MachineWorkSlots slots = new MachineWorkSlots(machine, List.of());
        private final EnergyBuffer energy = new EnergyBuffer(100, () -> {
        });
        private final RecipeProcess process;

        private RecipeFixture() {
            assertEquals(100, energy.receiveEnergy(100, Actionable.MODULATE));
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy), List.of()));
            var io = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(10, 0, 0, 0, false));
            process = install(io);
        }

        private RecipeFixture(UnaryOperator<ResourceReservation> adapter) {
            assertEquals(100, energy.receiveEnergy(100, Actionable.MODULATE));
            var system = new RecipeRequirementSystem<String>() {
                @Override
                public RequirementTransaction start(RecipeProcess owner, RecipeRequirement<String> requirement) {
                    var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 10).reservation());
                    return new ReservedEnergy(adapter.apply(token));
                }
            };
            process = install(new RecipeRequirementType<>(id("custom_energy"), system).requirement(id("custom_energy"), "value"));
        }

        private RecipeProcess install(RecipeRequirement<?> requirement) {
            var installed = new RecipeProcess(machine, new MachineRecipe(id("recipe"), 0, List.of(requirement)), 1);
            assertTrue(slots.start(installed));
            return installed;
        }
    }

    private record ReservedEnergy(ResourceReservation token) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public List<ResourceReservation> reservations() {
            return List.of(token);
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }

    private static class ReservationAdapter implements ResourceReservation {
        private final ResourceReservation actual;

        private ReservationAdapter(ResourceReservation actual) {
            this.actual = actual;
        }

        @Override
        public StorageContext context() {
            return actual.context();
        }

        @Override
        public boolean canApply() {
            return actual.canApply();
        }

        @Override
        public void apply() {
            actual.apply();
        }

        @Override
        public void complete() {
            actual.complete();
        }

        @Override
        public void rollback() {
            actual.rollback();
        }

        @Override
        public Resolution resolution() {
            return actual.resolution();
        }
    }

    private static final class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        private final ExecutorService lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());

        private TaskScheduler scheduler(TaskScheduler.PlannedFactory factory) {
            return new TaskScheduler(Thread.currentThread(), 2, 1, workers, new ExecutorService[]{lane}, factory, TIMEOUT);
        }

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
