package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SchedulerBoundaryTest {
    private static ExecutorService auxiliary() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test handshake");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void holdUntilChecked(CountDownLatch entered, CountDownLatch cleanup, CountDownLatch release) {
        try {
            await(entered);
            assertFalse(cleanup.await(100, TimeUnit.MILLISECONDS), "Cleanup ran while an accepted worker still owned its task");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        } finally {
            release.countDown();
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void mainSelfCleanupRunsAfterCallbackExitAndBeforeConcurrentCapture() {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        boolean[] callbackActive = {false};
        Task[] self = {null};
        try (var scheduler = new TaskScheduler(owner, 1, 1)) {
            var removed = new Task(ExecutionMode.CONCURRENT, () -> fail("Removed machine executed"));
            var added = new Task(ExecutionMode.CONCURRENT, () -> log.add("new-worker")) {
                @Override
                public Set<?> affinityKeys() {
                    log.add("capture");
                    return Set.of();
                }
            };
            self[0] = new Task(ExecutionMode.MAIN_THREAD, () -> {
                callbackActive[0] = true;
                log.add("main-enter");
                try {
                    scheduler.runAtBoundary(() -> {
                        assertSame(owner, Thread.currentThread());
                        assertFalse(callbackActive[0]);
                        log.add("self-cleanup");
                        scheduler.unregister(removed);
                        scheduler.unregister(self[0]);
                        self[0].mode = ExecutionMode.CONCURRENT;
                        scheduler.register(self[0]);
                        scheduler.register(added);
                    });
                    assertEquals(List.of("one-shot", "one-cleanup", "main-enter"), List.copyOf(log));
                } finally {
                    callbackActive[0] = false;
                    log.add("main-exit");
                }
            });
            scheduler.register(self[0]);
            scheduler.register(removed);
            scheduler.submit(() -> {
                scheduler.runAtBoundary(() -> log.add("one-cleanup"));
                log.add("one-shot");
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(List.of("one-shot", "one-cleanup", "main-enter", "main-exit", "self-cleanup", "capture", "new-worker"), List.copyOf(log));
        }
    }

    @Test
    void failingMainCallbackStillDrainsEveryCleanupAndAggregatesTheirFailures() {
        var mainFailure = new IllegalStateException("main callback failed");
        var firstCleanup = new IllegalArgumentException("first cleanup failed");
        var secondCleanup = new AssertionError("second cleanup failed");
        var log = new ConcurrentLinkedQueue<String>();
        boolean[] callbackActive = {false};
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            scheduler.submit(() -> {
                callbackActive[0] = true;
                try {
                    scheduler.runAtBoundary(() -> {
                        assertFalse(callbackActive[0]);
                        log.add("first");
                        throw firstCleanup;
                    });
                    scheduler.runAtBoundary(() -> {
                        log.add("second");
                        throw secondCleanup;
                    });
                    scheduler.runAtBoundary(() -> log.add("last"));
                    throw mainFailure;
                } finally {
                    callbackActive[0] = false;
                }
            }, ExecutionMode.MAIN_THREAD);
            scheduler.submit(() -> fail("Ordinary work continued after boundary failure"), ExecutionMode.MAIN_THREAD);
            assertSame(mainFailure, assertThrows(IllegalStateException.class, scheduler::tick));
            assertArrayEquals(new Throwable[]{firstCleanup, secondCleanup}, mainFailure.getSuppressed());
            assertEquals(List.of("first", "second", "last"), List.copyOf(log));
            assertThrows(IllegalStateException.class, scheduler::tick);
            scheduler.runAtBoundary(() -> log.add("after-fault"));
            assertEquals(List.of("first", "second", "last", "after-fault"), List.copyOf(log));
        }
    }

    @Test
    void workerCleanupWaitsForEveryAcceptedCallbackEvenWhenAnotherWorkerFails() throws Exception {
        Thread owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var cleanup = new CountDownLatch(1);
        var failure = new IllegalStateException("worker failed");
        try (var scheduler = new TaskScheduler(owner, 2, 1); var observer = auxiliary()) {
            scheduler.submit(() -> {
                scheduler.runAtBoundary(() -> {
                    assertSame(owner, Thread.currentThread());
                    assertEquals(0, exited.getCount());
                    cleanup.countDown();
                });
                entered.countDown();
                try {
                    await(release);
                } finally {
                    exited.countDown();
                }
            }, ExecutionMode.CONCURRENT);
            scheduler.submit(() -> {
                await(entered);
                throw failure;
            }, ExecutionMode.CONCURRENT);
            var observation = observer.submit(() -> holdUntilChecked(entered, cleanup, release));
            try {
                assertSame(failure, assertThrows(IllegalStateException.class, scheduler::tick));
                assertEquals(0, cleanup.getCount());
                observation.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void interruptedOwnerJoinsWorkerBeforeCleanupAndRestoresInterrupt() throws Exception {
        Thread owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var cleanup = new CountDownLatch(1);
        try (var scheduler = new TaskScheduler(owner, 1, 1); var interrupter = auxiliary()) {
            scheduler.submit(() -> {
                scheduler.runAtBoundary(() -> {
                    assertEquals(0, exited.getCount());
                    cleanup.countDown();
                });
                entered.countDown();
                try {
                    await(release);
                } finally {
                    exited.countDown();
                }
            }, ExecutionMode.CONCURRENT);
            var interruption = interrupter.submit(() -> {
                await(entered);
                owner.interrupt();
                holdUntilChecked(entered, cleanup, release);
            });
            try {
                var failure = assertThrows(IllegalStateException.class, scheduler::tick);
                assertInstanceOf(InterruptedException.class, failure.getCause());
                assertTrue(owner.isInterrupted());
                assertEquals(0, cleanup.getCount());
            } finally {
                clearInterrupt();
                release.countDown();
            }
            interruption.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, scheduler::tick);
        } finally {
            clearInterrupt();
        }
    }

    @Test
    void idleAndNestedRequestsDrainInFifoOrderWithoutReentry() throws Exception {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(owner, 1, 1); var sender = auxiliary()) {
            sender.submit(() -> {
                scheduler.runAtBoundary(() -> {
                    log.add("first-enter");
                    scheduler.runAtBoundary(() -> log.add("nested"));
                    assertThrows(IllegalStateException.class, scheduler::tick);
                    assertThrows(IllegalStateException.class, scheduler::close);
                    log.add("first-exit");
                });
                scheduler.runAtBoundary(() -> log.add("second"));
            }).get(5, TimeUnit.SECONDS);
            assertTrue(log.isEmpty());
            scheduler.runAtBoundary(() -> {
                assertSame(owner, Thread.currentThread());
                log.add("third");
            });
            assertEquals(List.of("first-enter", "first-exit", "second", "third", "nested"), List.copyOf(log));
        }
    }

    @Test
    void metadataCannotRequestOwnerActionsAndBoundaryMutationKeepsMetadataGuard() {
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            var guarded = new Task(ExecutionMode.CONCURRENT, () -> log.add("worker")) {
                @Override
                public ExecutionMode executionMode() {
                    rejectRequest();
                    return super.executionMode();
                }

                @Override
                public boolean active() {
                    rejectRequest();
                    return super.active();
                }

                @Override
                public Set<?> affinityKeys() {
                    rejectRequest();
                    return Set.of();
                }

                private void rejectRequest() {
                    assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> fail("Metadata action accepted")));
                }
            };
            scheduler.runAtBoundary(() -> scheduler.register(guarded));
            scheduler.tick();
            scheduler.runAtBoundary(() -> scheduler.unregister(guarded));
            assertEquals(List.of("worker"), List.copyOf(log));
            assertEquals(0, scheduler.registeredCount());
        }
    }

    @Test
    void faultedTickStillDrainsForeignCleanupWhileKeepingOrdinaryAdmissionRevoked() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1); var sender = auxiliary()) {
            var machine = new Task(ExecutionMode.MAIN_THREAD, () -> {
                throw new IllegalStateException("machine failed");
            });
            scheduler.register(machine);
            assertThrows(IllegalStateException.class, scheduler::tick);
            sender.submit(() -> scheduler.runAtBoundary(() -> {
                scheduler.unregister(machine);
                assertThrows(IllegalStateException.class, () -> scheduler.register(machine));
                assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> {
                }, ExecutionMode.MAIN_THREAD));
                log.add("cleanup");
            })).get(5, TimeUnit.SECONDS);
            assertTrue(log.isEmpty());
            assertThrows(IllegalStateException.class, scheduler::tick);
            assertEquals(List.of("cleanup"), List.copyOf(log));
            assertEquals(0, scheduler.registeredCount());
        }
    }

    @Test
    @SuppressWarnings("resource")
        // Injected lanes transfer ownership to the scheduler.
    void closeDrainsForeignAndLateRequestsAfterOwnedExecutorsTerminate() throws Exception {
        Thread owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var shutdown = new CountDownLatch(1);
        var log = new ConcurrentLinkedQueue<String>();
        var workers = new ShutdownExecutor(shutdown, false);
        workers.execute(() -> {
            entered.countDown();
            try {
                await(release);
            } finally {
                exited.countDown();
            }
        });
        await(entered);
        try (var scheduler = new TaskScheduler(owner, 1, 1, workers, new ExecutorService[]{auxiliary()}); var sender = auxiliary()) {
            var machine = new Task(ExecutionMode.MAIN_THREAD, () -> fail("Closing machine executed"));
            scheduler.register(machine);
            sender.submit(() -> scheduler.runAtBoundary(() -> {
                assertSame(owner, Thread.currentThread());
                assertEquals(0, exited.getCount());
                scheduler.unregister(machine);
                assertThrows(IllegalStateException.class, () -> scheduler.register(machine));
                log.add("first");
            })).get(5, TimeUnit.SECONDS);
            var late = sender.submit(() -> {
                await(shutdown);
                scheduler.runAtBoundary(() -> log.add("late"));
                assertTrue(log.isEmpty());
                release.countDown();
            });
            try {
                scheduler.close();
            } finally {
                release.countDown();
            }
            late.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("first", "late"), List.copyOf(log));
            assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> {
            }));
            sender.submit(() -> assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> {
            }))).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    @SuppressWarnings("resource")
        // Injected lanes transfer ownership to the scheduler.
    void failedCloseWithoutTerminationRetainsActionsAndCannotDrainAtIdleOrFaultedTick() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var log = new ConcurrentLinkedQueue<String>();
        var workers = new ShutdownExecutor(new CountDownLatch(1), true);
        workers.execute(() -> {
            entered.countDown();
            try {
                await(release);
            } finally {
                exited.countDown();
            }
        });
        await(entered);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1, workers, new ExecutorService[]{auxiliary()}); var sender = auxiliary()) {
            sender.submit(() -> scheduler.runAtBoundary(() -> {
                assertEquals(0, exited.getCount());
                log.add("queued-before-close");
            })).get(5, TimeUnit.SECONDS);
            assertSame(workers.failure, assertThrows(IllegalStateException.class, scheduler::close));
            assertFalse(workers.isTerminated());
            scheduler.runAtBoundary(() -> log.add("queued-after-close"));
            assertThrows(IllegalStateException.class, scheduler::tick);
            assertTrue(log.isEmpty());
            workers.failShutdown = false;
            release.countDown();
            scheduler.close();
            assertEquals(List.of("queued-before-close", "queued-after-close"), List.copyOf(log));
            assertTrue(workers.isTerminated());
            assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> {
            }));
        } finally {
            workers.failShutdown = false;
            release.countDown();
            workers.shutdown();
        }
    }

    @Test
    @SuppressWarnings("resource")
        // Injected lanes transfer ownership to the scheduler.
    void shutdownFailureWithProvenTerminationStillDrainsAndClosesOwnerAdmission() throws Exception {
        var workers = new ShutdownExecutor(new CountDownLatch(1), false);
        workers.failAfterShutdown = true;
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1, workers, new ExecutorService[]{auxiliary()}); var sender = auxiliary()) {
            sender.submit(() -> scheduler.runAtBoundary(() -> {
                assertTrue(workers.isTerminated());
                log.add("cleanup");
            })).get(5, TimeUnit.SECONDS);
            assertSame(workers.failure, assertThrows(IllegalStateException.class, scheduler::close));
            assertEquals(List.of("cleanup"), List.copyOf(log));
            assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> {
            }));
        }
    }

    @Test
    void closeCleanupFailuresStillDrainNestedActionsAndCloseAdmission() throws Exception {
        var first = new IllegalStateException("first cleanup failed");
        var second = new IllegalArgumentException("second cleanup failed");
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1); var sender = auxiliary()) {
            sender.submit(() -> {
                scheduler.runAtBoundary(() -> {
                    log.add("first");
                    scheduler.runAtBoundary(() -> log.add("nested"));
                    throw first;
                });
                scheduler.runAtBoundary(() -> {
                    log.add("second");
                    throw second;
                });
            }).get(5, TimeUnit.SECONDS);
            assertSame(first, assertThrows(IllegalStateException.class, scheduler::close));
            assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
            assertEquals(List.of("first", "second", "nested"), List.copyOf(log));
            assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> {
            }));
        }
    }

    private static class Task implements ScheduledTask {
        private final Runnable action;
        private ExecutionMode mode;

        private Task(ExecutionMode mode, Runnable action) {
            this.mode = mode;
            this.action = action;
        }

        @Override
        public void onSchedule() {
            action.run();
        }

        @Override
        public ExecutionMode executionMode() {
            return mode;
        }

        @Override
        public boolean active() {
            return true;
        }
    }

    private static final class ShutdownExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = auxiliary();
        private final CountDownLatch shutdown;
        private final IllegalStateException failure = new IllegalStateException("shutdown failed before termination");
        private boolean failShutdown;
        private boolean failAfterShutdown;

        private ShutdownExecutor(CountDownLatch shutdown, boolean failShutdown) {
            this.shutdown = shutdown;
            this.failShutdown = failShutdown;
        }

        @Override
        public void execute(@NotNull Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            if (failShutdown) {
                throw failure;
            }
            delegate.shutdown();
            shutdown.countDown();
            if (failAfterShutdown) {
                throw failure;
            }
        }

        @Override
        public @NotNull List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
