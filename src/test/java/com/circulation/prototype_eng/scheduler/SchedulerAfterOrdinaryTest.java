package com.circulation.prototype_eng.scheduler;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
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
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
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
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SchedulerAfterOrdinaryTest {
    private static void enqueueBoundary(TaskScheduler scheduler, Runnable action) throws Exception {
        var queued = new FutureTask<Void>(() -> {
            scheduler.runAtBoundary(action);
            return null;
        });
        Thread.ofPlatform().daemon().start(queued);
        queued.get(5, TimeUnit.SECONDS);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("scheduler_after_ordinary_test", name);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch, CountDownLatch interrupted) {
        boolean exited = false;
        while (!exited) {
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                exited = true;
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    @Test
    void pendingResearchRetainsFollowingSubmissionsAndBoundariesUntilSettlement() throws Exception {
        var work = new Work();
        var ready = new AtomicBoolean();
        var captures = new AtomicInteger();
        var afterCaptures = new AtomicInteger();
        var ordinary = new AtomicInteger();
        var prepare = new AtomicInteger();
        var boundaries = new AtomicInteger();
        var saved = new AtomicInteger();
        var retained = new AtomicReference<FollowingWork>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((_, _) -> {
                int capture = afterCaptures.incrementAndGet();
                assertEquals(capture == 1 ? 2 : 3, ordinary.get());
                assertEquals(capture - 1, boundaries.get());
                return null;
            }, (tasks, deadline) -> {
                if (captures.incrementAndGet() != 1) {
                    assertTrue(retained.get().finished());
                    return null;
                }
                var cycle = new FollowingWork(tasks, deadline, work, ready, saved::incrementAndGet);
                retained.set(cycle);
                return cycle;
            });
            scheduler.submit(ordinary::incrementAndGet, ExecutionMode.MAIN_THREAD);
            scheduler.submit(ordinary::incrementAndGet, ExecutionMode.CONCURRENT);
            scheduler.tick(prepare::incrementAndGet);
            assertFalse(retained.get().finished());
            assertEquals(1, captures.get());
            assertEquals(1, saved.get());
            assertEquals(2, ordinary.get());
            assertTrue(prepare.get() > 0);
            assertEquals(1, afterCaptures.get());
            scheduler.submit(ordinary::incrementAndGet, ExecutionMode.MAIN_THREAD);
            enqueueBoundary(scheduler, () -> {
                assertTrue(retained.get().finished());
                assertEquals(2, ordinary.get());
                boundaries.incrementAndGet();
            });
            scheduler.tick(prepare::incrementAndGet);
            assertEquals(1, captures.get(), "Pending cleanup cannot recapture the following work factory");
            assertEquals(1, saved.get(), "An uncertain completion is queried rather than written again");
            assertEquals(2, ordinary.get());
            assertEquals(0, boundaries.get());
            ready.set(true);
            scheduler.tick(prepare::incrementAndGet);
            assertTrue(retained.get().finished());
            assertEquals(2, captures.get(), "Once old cleanup finishes, this tick may capture one fresh following work");
            assertEquals(3, ordinary.get());
            assertEquals(1, boundaries.get());
            assertEquals(2, afterCaptures.get());
            assertEquals(1, work.applies.get());
            assertEquals(1, work.post.get());
            scheduler.close();
        } finally {
            ready.set(true);
        }
    }

    @Test
    void pendingMachineWorkIsRecoveredBeforeResearchOrFreshOrdinaryPreparation() {
        var work = new Work();
        var ready = new AtomicBoolean();
        var beforeCaptures = new AtomicInteger();
        var afterCaptures = new AtomicInteger();
        var ordinary = new AtomicInteger();
        var retained = new AtomicReference<FollowingWork>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((tasks, deadline) -> {
                if (afterCaptures.incrementAndGet() != 1) {
                    return null;
                }
                var cycle = new FollowingWork(tasks, deadline, work, ready, () -> {
                });
                retained.set(cycle);
                return cycle;
            }, (_, _) -> {
                if (beforeCaptures.incrementAndGet() > 1) {
                    assertTrue(retained.get().finished());
                }
                return null;
            });
            scheduler.submit(ordinary::incrementAndGet, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(1, ordinary.get());
            assertEquals(0, beforeCaptures.get());
            assertFalse(retained.get().finished());
            scheduler.submit(ordinary::incrementAndGet, ExecutionMode.MAIN_THREAD);
            var blockedPrepare = new AtomicInteger();
            scheduler.tick(blockedPrepare::incrementAndGet);
            assertEquals(0, blockedPrepare.get());
            assertEquals(0, beforeCaptures.get());
            assertEquals(1, ordinary.get());
            ready.set(true);
            scheduler.tick();
            assertEquals(1, beforeCaptures.get());
            assertEquals(2, afterCaptures.get());
            assertEquals(2, ordinary.get());
            assertEquals(1, work.applies.get());
            scheduler.close();
        } finally {
            ready.set(true);
        }
    }

    @Test
    void researchCaptureRunsAfterOrdinaryAndPlannedMachineWork() {
        var order = new ObjectArrayList<String>();
        try (var backend = new Backend()) {
            TaskScheduler[] scheduler = {null};
            scheduler[0] = backend.scheduler((_, _) -> {
                order.add("machine");
                return null;
            }, (_, _) -> {
                assertThrows(IllegalStateException.class, () -> scheduler[0].runAtBoundary(() -> fail("Capture cannot acquire boundary work")));
                assertThrows(IllegalStateException.class, scheduler[0]::tick);
                order.add("research");
                return null;
            });
            scheduler[0].submit(() -> order.add("ordinary"), ExecutionMode.MAIN_THREAD);
            scheduler[0].tick();
            assertEquals(List.of("ordinary", "machine", "research"), order);
            scheduler[0].close();
        }
    }

    @Test
    void aThrowingFollowingWorkCompletionRetainsItsExactObligationForRecoveryAndClose() throws Exception {
        var work = new Work();
        var ready = new AtomicBoolean();
        var saves = new AtomicInteger();
        var beforeCaptures = new AtomicInteger();
        var afterCaptures = new AtomicInteger();
        var boundaries = new AtomicInteger();
        var ordinary = new AtomicInteger();
        var failure = new IllegalStateException("completion proof unknown");
        var retained = new AtomicReference<FollowingWork>();
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler((_, _) -> {
                afterCaptures.incrementAndGet();
                return null;
            }, (tasks, deadline) -> {
                beforeCaptures.incrementAndGet();
                var cycle = new FollowingWork(tasks, deadline, work, ready, () -> {
                    saves.incrementAndGet();
                    throw failure;
                });
                retained.set(cycle);
                return cycle;
            });
            scheduler.submit(ordinary::incrementAndGet, ExecutionMode.MAIN_THREAD);
            assertSame(failure, assertThrows(IllegalStateException.class, scheduler::tick));
            assertFalse(retained.get().finished());
            assertEquals(1, work.applies.get());
            assertEquals(1, saves.get());
            assertEquals(1, ordinary.get());
            enqueueBoundary(scheduler, boundaries::incrementAndGet);
            scheduler.tick();
            assertEquals(1, saves.get());
            assertEquals(1, beforeCaptures.get());
            assertFalse(scheduler.tryClose());
            assertFalse(backend.lane.isShutdown());
            assertEquals(0, boundaries.get());
            ready.set(true);
            assertTrue(scheduler.tryClose());
            assertTrue(retained.get().finished());
            assertEquals(1, saves.get());
            assertEquals(1, beforeCaptures.get(), "Close never starts fresh following work business");
            assertEquals(1, afterCaptures.get());
            assertEquals(1, boundaries.get());
            assertEquals(1, ordinary.get());
        } finally {
            ready.set(true);
        }
    }

    @Test
    void unknownPhysicalCompletionKeepsOriginalSourceLaneAndPreventsNewPreparationThroughClose() throws Exception {
        var work = new Work();
        var proof = new AtomicBoolean();
        var completes = new AtomicInteger();
        var captures = new AtomicInteger();
        var boundaries = new AtomicInteger();
        var prepare = new AtomicInteger();
        work.adapter = token -> new ReservationAdapter(token) {
            @Override
            public void complete() {
                super.complete();
                completes.incrementAndGet();
                throw new IllegalStateException("source proof withheld");
            }

            @Override
            public Resolution resolution() {
                return proof.get() ? super.resolution() : Resolution.UNKNOWN;
            }
        };
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler(null, (tasks, deadline) -> {
                captures.incrementAndGet();
                return new FollowingWork(tasks, deadline, work, new AtomicBoolean(true), () -> {
                });
            });
            assertEquals("source proof withheld", assertThrows(IllegalStateException.class, () -> scheduler.tick(prepare::incrementAndGet)).getMessage());
            assertEquals(1, prepare.get());
            assertEquals(0, work.post.get());
            assertEquals(1, completes.get());
            enqueueBoundary(scheduler, boundaries::incrementAndGet);
            scheduler.tick(prepare::incrementAndGet);
            assertEquals(1, captures.get());
            assertEquals(1, completes.get());
            assertEquals(1, prepare.get());
            assertFalse(scheduler.tryClose());
            assertFalse(backend.lane.isShutdown());
            assertEquals(0, boundaries.get());
            proof.set(true);
            assertTrue(scheduler.tryClose());
            assertEquals(1, boundaries.get());
            assertEquals(1, completes.get());
            assertEquals(1, work.applies.get());
            assertEquals(1, work.post.get());
            assertEquals(90, work.energy.savedEnergy());
            assertTrue(backend.lane.isTerminated());
        } finally {
            proof.set(true);
        }
    }

    @Test
    void researchCaptureOwnsSourceExitBeforeQueuedBoundaryOrNextTick() throws Exception {
        var work = new Work();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var awaitingExit = new CountDownLatch(1);
        var sourceExited = new AtomicBoolean();
        var tickReturned = new AtomicBoolean();
        var ordinary = new AtomicInteger();
        var prepare = new AtomicInteger();
        var boundaries = new AtomicInteger();
        var captures = new AtomicInteger();
        work.adapter = token -> new ReservationAdapter(token) {
            @Override
            public void apply() {
                super.apply();
                entered.countDown();
                awaitIgnoringInterrupt(release, interrupted);
                sourceExited.set(true);
            }
        };
        try (var backend = new Backend()) {
            var scheduler = backend.scheduler(null, (tasks, deadline) -> {
                captures.incrementAndGet();
                var cycle = new FollowingWork(tasks, deadline, work, new AtomicBoolean(true), () -> {
                });
                cycle.awaitNotice = awaitingExit;
                return cycle;
            });
            scheduler.submit(() -> {
                assertFalse(sourceExited.get());
                ordinary.incrementAndGet();
            }, ExecutionMode.MAIN_THREAD);
            var cancel = new FutureTask<Void>(() -> {
                await(entered);
                scheduler.runAtBoundary(() -> {
                    assertTrue(sourceExited.get());
                    boundaries.incrementAndGet();
                });
                work.token.cancel(Reason.EXPLICIT_CANCEL);
                await(interrupted);
                await(awaitingExit);
                assertFalse(tickReturned.get());
                assertTrue(prepare.get() > 0);
                assertEquals(0, boundaries.get());
                assertFalse(backend.lane.isShutdown());
                release.countDown();
                return null;
            });
            Thread.ofPlatform().daemon().start(cancel);
            try {
                scheduler.tick(() -> {
                    assertFalse(sourceExited.get());
                    prepare.incrementAndGet();
                });
                tickReturned.set(true);
                cancel.get(5, TimeUnit.SECONDS);
                assertTrue(sourceExited.get());
                assertEquals(1, captures.get());
                assertEquals(1, ordinary.get());
                assertEquals(1, boundaries.get());
                assertEquals(100, work.energy.savedEnergy());
                assertEquals(1, work.post.get());
                scheduler.close();
            } finally {
                release.countDown();
                cancel.get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
        }
    }

    private static final class FollowingWork implements PlannedExecution {
        private final PlannedTaskExecutor tasks;
        private final PlannedDeadline forwardDeadline;
        private final Work work;
        private final AtomicBoolean ready;
        private final Runnable completion;
        private PlannedRecipeCoordinator coordinator;
        private boolean completionAttempted;
        private boolean finished;
        private CountDownLatch awaitNotice = new CountDownLatch(0);

        private FollowingWork(PlannedTaskExecutor tasks, PlannedDeadline forwardDeadline, Work work, AtomicBoolean ready, Runnable completion) {
            this.tasks = tasks;
            this.forwardDeadline = forwardDeadline;
            this.work = work;
            this.ready = ready;
            this.completion = completion;
        }

        @Override
        public boolean advance(PlannedDeadline cleanupDeadline) {
            if (finished) {
                return true;
            }
            if (coordinator == null) {
                coordinator = PlannedRecipeCoordinator.forProcess(tasks, work.process, forwardDeadline, work.token, () -> true, _ -> List.of());
            }
            if (!coordinator.advance(cleanupDeadline)) {
                return false;
            }
            if (!completionAttempted) {
                completionAttempted = true;
                completion.run();
            }
            finished = ready.get();
            return finished;
        }

        @Override
        public void awaitExit() {
            awaitNotice.countDown();
            if (coordinator != null) {
                coordinator.awaitExit();
            }
        }

        @Override
        public boolean finished() {
            return finished;
        }

        @Override
        public void cancel(Reason reason) {
            work.token.cancel(reason);
        }
    }

    private static final class Work {
        private final EnergyBuffer energy = new EnergyBuffer(100, () -> {
        });
        private final PlannedCancellationToken token = new PlannedCancellationToken();
        private final AtomicInteger applies = new AtomicInteger();
        private final AtomicInteger post = new AtomicInteger();
        private final RecipeProcess process;
        private UnaryOperator<ResourceReservation> adapter = UnaryOperator.identity();

        private Work() {
            energy.receiveEnergy(100, Actionable.MODULATE);
            var system = new RecipeRequirementSystem<String>() {
                @Override
                public RequirementTransaction start(RecipeProcess current, RecipeRequirement<String> requirement) {
                    var original = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 10).reservation());
                    var held = adapter.apply(new ReservationAdapter(original) {
                        @Override
                        public void apply() {
                            applies.incrementAndGet();
                            super.apply();
                        }
                    });
                    return new HeldResource(held);
                }
            };
            var requirement = new RecipeRequirementType<>(id("energy"), system).requirement(id("energy"), "value");
            process = RecipeProcess.materialCycle(new MachineInstance(), id("cycle"), List.of(requirement),
                new MachineHatches(List.of(), List.of(energy), List.of()), 1);
            process.components().create(new ComponentType<>(id("post"), _ -> new Object(), List.of(new ComponentSystem<RecipeProcess, Object>() {
                @Override
                public ResourceLocation id() {
                    return SchedulerAfterOrdinaryTest.id("post");
                }

                @Override
                public void onPostTick(RecipeProcess current, Object component) {
                    post.incrementAndGet();
                }
            })), process);
        }
    }

    private record HeldResource(ResourceReservation token) implements RequirementTransaction {
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
        private final ResourceReservation token;

        private ReservationAdapter(ResourceReservation token) {
            this.token = token;
        }

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
            token.complete();
        }

        @Override
        public void rollback() {
            token.rollback();
        }

        @Override
        public Resolution resolution() {
            return token.resolution();
        }
    }

    private static final class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        private final ExecutorService lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());

        private TaskScheduler scheduler(TaskScheduler.PlannedFactory after, TaskScheduler.PlannedFactory before) {
            return new TaskScheduler(Thread.currentThread(), 2, 1, workers, new ExecutorService[]{lane}, after, before, Duration.ofSeconds(5));
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
