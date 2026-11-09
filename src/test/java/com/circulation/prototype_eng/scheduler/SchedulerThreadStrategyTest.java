package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.scheduler.SchedulerConfig.WorkerStrategy;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerThreadStrategyTest {
    private static SchedulerConfig.Settings virtualSettings() {
        return new SchedulerConfig.Settings(1, 1).withWorkerStrategy(WorkerStrategy.VIRTUAL_PER_TASK);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Test handshake must complete");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static FutureTask<Void> observe(Runnable action) {
        var task = new FutureTask<Void>(action, null);
        Thread.ofPlatform().daemon().start(task);
        return task;
    }

    @Test
    void virtualPerTaskActuallyOverlapsWithOneConfiguredWorkerAndStructureUsesTheSelectedRunner() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), virtualSettings())) {
            Runnable work = () -> {
                assertTrue(Thread.currentThread().isVirtual());
                entered.countDown();
                await(release);
                calls.incrementAndGet();
            };
            scheduler.submit(work, ExecutionMode.CONCURRENT);
            scheduler.submit(work, ExecutionMode.CONCURRENT);
            var observer = observe(() -> {
                try {
                    await(entered);
                } finally {
                    release.countDown();
                }
            });
            scheduler.tick();
            observer.get(5, TimeUnit.SECONDS);
            assertEquals(2, calls.get(), "Virtual mode does not impose the configured one-worker cap");
            scheduler.runAtBoundary(() -> assertTrue(scheduler.runStructureWorker(() -> Thread.currentThread().isVirtual(),
                PlannedDeadline.after(Duration.ofSeconds(5))) instanceof PlannedTaskExecutor.Success<Boolean>(
                var virtual
            ) && virtual));
            scheduler.requestSettings(scheduler.effectiveSettings().withWorkerStrategy(WorkerStrategy.PLATFORM_POOL));
            scheduler.tick();
            scheduler.runAtBoundary(() -> assertTrue(scheduler.runStructureWorker(() -> Thread.currentThread().isVirtual(),
                PlannedDeadline.after(Duration.ofSeconds(5))) instanceof PlannedTaskExecutor.Success<Boolean>(
                var virtual
            ) && !virtual));
        } finally {
            release.countDown();
        }
    }

    @Test
    void separateAcceptedSourceJobsUseOneVirtualSerializedLane() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var firstThread = new AtomicReference<Thread>();
        var secondThread = new AtomicReference<Thread>();
        var offered = new AtomicBoolean();
        class SourceCycle implements PlannedExecution {
            private final PlannedCancellationToken token = new PlannedCancellationToken();
            private final PlannedTaskExecutor tasks;
            private final PlannedDeadline forward;
            private PlannedTaskExecutor.Batch<Integer> batch;

            SourceCycle(PlannedTaskExecutor tasks, PlannedDeadline forward) {
                this.tasks = tasks;
                this.forward = forward;
            }

            @Override
            public boolean advance(PlannedDeadline cleanup) {
                if (batch == null) tasks.runOnSourceLanes(List.of(
                    new PlannedTaskExecutor.SourceJob<>("same-source", new PlannedTaskExecutor.Job<>(token, () -> {
                        firstThread.set(Thread.currentThread());
                        entered.countDown();
                        await(release);
                        return 1;
                    })),
                    new PlannedTaskExecutor.SourceJob<>("same-source", new PlannedTaskExecutor.Job<>(token, () -> {
                        secondThread.set(Thread.currentThread());
                        second.countDown();
                        return 2;
                    }))), forward, owned -> batch = owned);
                assertInstanceOf(PlannedTaskExecutor.Success.class, batch.results().get(0));
                assertInstanceOf(PlannedTaskExecutor.Success.class, batch.results().get(1));
                return true;
            }

            @Override
            public void awaitExit() {
                if (batch != null) batch.awaitQuiescence();
            }

            @Override
            public boolean finished() {
                return batch != null && batch.quiescent();
            }

            @Override
            public void cancel(Reason reason) {
                token.cancel(reason);
            }
        }
        try (var scheduler = new TaskScheduler(Thread.currentThread(), virtualSettings(),
            (tasks, deadline) -> offered.compareAndSet(false, true) ? new SourceCycle(tasks, deadline) : null, null)) {
            var observer = observe(() -> {
                try {
                    await(entered);
                    try {
                        assertFalse(second.await(30, TimeUnit.MILLISECONDS), "Separate jobs sharing a lane must not overlap");
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                } finally {
                    release.countDown();
                }
            });
            scheduler.tick();
            observer.get(5, TimeUnit.SECONDS);
            assertTrue(firstThread.get().isVirtual());
            assertSame(firstThread.get(), secondThread.get(), "Both independently dispatched source jobs use the same virtual lane worker");
        } finally {
            release.countDown();
        }
    }

    @Test
    void modeReloadWaitsForRealStoppedSourceExitAndWholeCleanupThenKeepsRegistrationOnceQueueAndDeadline() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var awaitEntered = new CountDownLatch(1);
        var sourceExited = new AtomicBoolean();
        var tickReturned = new AtomicBoolean();
        var ready = new AtomicBoolean();
        var sourceThread = new AtomicReference<Thread>();
        var sourceCalls = new AtomicInteger();
        var registeredCalls = new AtomicInteger();
        var onceCalls = new AtomicInteger();
        var boundaryCalls = new AtomicInteger();
        var captures = new AtomicInteger();
        var captureBudgets = new ArrayList<Long>();
        var token = new PlannedCancellationToken();
        var cycle = new AtomicReference<StoppedSourceCycle>();
        var initial = virtualSettings();
        var replacement = new SchedulerConfig.Settings(2, 2, Duration.ofMillis(1234), false, 200, 4, WorkerStrategy.PLATFORM_POOL);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), initial, (tasks, deadline) -> {
            captureBudgets.add(deadline.timeoutNanos());
            if (captures.getAndIncrement() != 0) return null;
            var owned = new StoppedSourceCycle(tasks, deadline, token, ready, sourceCalls, () -> {
                sourceThread.set(Thread.currentThread());
                assertTrue(Thread.currentThread().isVirtual());
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        done = true;
                    } catch (InterruptedException ignored) {
                        interrupted.countDown();
                    }
                }
                sourceExited.set(true);
            }, awaitEntered);
            cycle.set(owned);
            return owned;
        }, null)) {
            scheduler.register(new ScheduledTask() {
                @Override
                public void onSchedule() {
                    registeredCalls.incrementAndGet();
                }

                @Override
                public ExecutionMode executionMode() {
                    return ExecutionMode.MAIN_THREAD;
                }

                @Override
                public boolean active() {
                    return true;
                }
            });
            var observer = observe(() -> {
                try {
                    await(entered);
                    token.cancel(Reason.EXPLICIT_CANCEL);
                    await(interrupted);
                    await(awaitEntered);
                    assertFalse(tickReturned.get());
                    assertFalse(sourceExited.get());
                    assertEquals(0, boundaryCalls.get());
                } finally {
                    release.countDown();
                }
            });
            scheduler.tick();
            tickReturned.set(true);
            observer.get(5, TimeUnit.SECONDS);
            assertTrue(sourceExited.get());
            assertTrue(sourceThread.get().isAlive());
            var stopped = assertInstanceOf(PlannedTaskExecutor.Stopped.class, cycle.get().batch.results().getFirst());
            assertTrue(stopped.started());
            assertFalse(cycle.get().finished());
            scheduler.requestSettings(replacement);
            scheduler.submit(onceCalls::incrementAndGet, ExecutionMode.MAIN_THREAD);
            scheduler.submit(() -> {
                assertFalse(Thread.currentThread().isVirtual());
                onceCalls.incrementAndGet();
            }, ExecutionMode.CONCURRENT);
            scheduler.runAtBoundary(boundaryCalls::incrementAndGet);
            scheduler.tick();
            assertSame(initial, scheduler.effectiveSettings());
            assertSame(replacement, scheduler.pendingSettings());
            assertEquals(1, registeredCalls.get());
            assertEquals(0, onceCalls.get());
            assertEquals(0, boundaryCalls.get());
            assertTrue(sourceThread.get().isAlive());
            ready.set(true);
            scheduler.tick();
            assertFalse(sourceThread.get().isAlive(), "Original virtual source executor terminates only after whole cleanup");
            assertSame(replacement, scheduler.effectiveSettings());
            assertEquals(2, registeredCalls.get());
            assertEquals(2, onceCalls.get());
            assertEquals(1, boundaryCalls.get());
            assertEquals(1, sourceCalls.get());
            assertEquals(1, scheduler.registeredCount());
            assertEquals(List.of(initial.plannedTimeout().toNanos(), replacement.plannedTimeout().toNanos()), captureBudgets);
            assertTrue(cycle.get().cleanupBudgets.stream().allMatch(value -> value == initial.plannedTimeout().toNanos()));
            scheduler.tick();
            assertEquals(3, registeredCalls.get());
            assertEquals(2, onceCalls.get());
            assertEquals(1, sourceCalls.get());
        } finally {
            release.countDown();
        }
    }

    /** Holds an actual stopped source Batch; the post gate models retained owner cleanup after physical exit. */
    private static final class StoppedSourceCycle implements PlannedExecution {
        private final PlannedTaskExecutor tasks;
        private final PlannedDeadline forward;
        private final PlannedCancellationToken token;
        private final AtomicBoolean ready;
        private final AtomicInteger calls;
        private final Runnable source;
        private final CountDownLatch awaitEntered;
        private final List<Long> cleanupBudgets = new ArrayList<>();
        private PlannedTaskExecutor.Batch<Integer> batch;

        private StoppedSourceCycle(PlannedTaskExecutor tasks, PlannedDeadline forward, PlannedCancellationToken token,
                                   AtomicBoolean ready, AtomicInteger calls, Runnable source, CountDownLatch awaitEntered) {
            this.tasks = tasks;
            this.forward = forward;
            this.token = token;
            this.ready = ready;
            this.calls = calls;
            this.source = source;
            this.awaitEntered = awaitEntered;
        }

        @Override
        public boolean advance(PlannedDeadline cleanup) {
            cleanupBudgets.add(cleanup.timeoutNanos());
            if (batch == null) tasks.runOnSourceLanes(List.of(new PlannedTaskExecutor.SourceJob<>("actual-source",
                new PlannedTaskExecutor.Job<>(token, () -> {
                    calls.incrementAndGet();
                    source.run();
                    return 1;
                }))), forward, owned -> batch = owned);
            return finished();
        }

        @Override
        public void cancel(Reason reason) {
            token.cancel(reason);
        }

        @Override
        public void awaitExit() {
            awaitEntered.countDown();
            if (batch != null) batch.awaitQuiescence();
        }

        @Override
        public boolean finished() {
            return ready.get() && batch != null && batch.quiescent();
        }
    }
}
