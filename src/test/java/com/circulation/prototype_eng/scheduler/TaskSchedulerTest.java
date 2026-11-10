package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TaskSchedulerTest {
    private static Task task(ExecutionMode mode, Set<?> keys, Runnable action) {
        return new Task(mode, keys, action);
    }

    private static void record(ConcurrentLinkedQueue<String> log, ConcurrentLinkedQueue<Thread> threads, String name) {
        threads.add(Thread.currentThread());
        log.add(name);
    }

    private static ExecutorService auxiliary() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    // Clear the test owner's flag without masking an earlier assertion failure.
    private static void clearTestThreadInterrupt() {
        Thread.interrupted();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test handshake");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void holdUntilChecked(CountDownLatch entered, CountDownLatch returned, CountDownLatch release) {
        try {
            await(entered);
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS), "Tick returned while an accepted worker still owned its task");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        } finally {
            release.countDown();
        }
    }

    @Test
    void preparationCapturesEveryMainMutationBeforeTheNextActionAndConcurrentMetadata() {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        int[] current = {0};
        int[] captured = {-1};
        try (var scheduler = new TaskScheduler(owner, 1, 1)) {
            scheduler.submit(() -> {
                log.add("custom:" + captured[0]);
                current[0] = 1;
            }, ExecutionMode.MAIN_THREAD);
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                log.add("first:" + captured[0]);
                current[0] = 2;
            }));
            var inactive = task(ExecutionMode.MAIN_THREAD, Set.of(), () -> fail("Inactive task executed"));
            inactive.enabled = false;
            scheduler.register(inactive);
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                log.add("second:" + captured[0]);
                scheduler.runAtBoundary(() -> current[0] = 3);
            }));
            scheduler.register(new Task(ExecutionMode.CONCURRENT, Set.of("source"), () -> {
                assertNotSame(owner, Thread.currentThread());
                log.add("worker:" + captured[0]);
            }) {
                @Override
                public Set<?> affinityKeys() {
                    assertSame(owner, Thread.currentThread());
                    assertEquals(3, captured[0], "Prepare must precede worker metadata callbacks");
                    return super.affinityKeys();
                }
            });

            scheduler.tick(() -> {
                assertSame(owner, Thread.currentThread());
                assertThrows(IllegalStateException.class, () -> scheduler.register(inactive));
                assertThrows(IllegalStateException.class, () -> scheduler.unregister(inactive));
                assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> fail("Forbidden boundary executed")));
                assertThrows(IllegalStateException.class, scheduler::tick);
                assertThrows(IllegalStateException.class, scheduler::close);
                captured[0] = current[0];
                log.add("prepare:" + captured[0]);
            });

            assertEquals(List.of("prepare:0", "custom:0", "prepare:1", "first:1",
                "prepare:2", "second:2", "prepare:3", "worker:3"), List.copyOf(log));
        }
    }

    @Test
    void mainPreparationFailureRejectsTheActionAndWorkersWithoutRetryingAuthority() {
        var log = new ConcurrentLinkedQueue<String>();
        int[] preparations = {0};
        var primary = new IllegalStateException("authority capture failed");
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                log.add("first");
                scheduler.runAtBoundary(() -> log.add("cleanup"));
                scheduler.submit(() -> fail("Faulted next-tick work executed"), ExecutionMode.MAIN_THREAD);
            }));
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), () -> fail("Rejected main executed")));
            scheduler.register(task(ExecutionMode.CONCURRENT, Set.of(), () -> fail("Rejected worker executed")));

            assertSame(primary, assertThrows(IllegalStateException.class, () -> scheduler.tick(() -> {
                if (++preparations[0] == 2) {
                    throw primary;
                }
            })));
            assertEquals(List.of("first", "cleanup"), List.copyOf(log));
            assertEquals(2, preparations[0]);
            assertThrows(IllegalStateException.class, () -> scheduler.tick(() -> fail("Faulted preparation retried")));
            assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> {
            }, ExecutionMode.CONCURRENT));
            scheduler.runAtBoundary(() -> log.add("fault cleanup"));
            assertEquals(List.of("first", "cleanup", "fault cleanup"), List.copyOf(log));
        }
    }

    @Test
    void concurrentPreparationFailureNeverDispatchesTheCapturedBatch() {
        int[] preparations = {0};
        int[] mains = {0};
        var primary = new IllegalArgumentException("concurrent authority failed");
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            scheduler.submit(() -> mains[0]++, ExecutionMode.MAIN_THREAD);
            scheduler.submit(() -> fail("Rejected custom worker executed"), ExecutionMode.CONCURRENT);
            var worker = task(ExecutionMode.CONCURRENT, Set.of(), () -> fail("Rejected registered worker executed"));
            scheduler.register(worker);

            assertSame(primary, assertThrows(IllegalArgumentException.class, () -> scheduler.tick(() -> {
                if (++preparations[0] == 2) {
                    throw primary;
                }
            })));
            assertEquals(1, mains[0]);
            assertEquals(2, preparations[0]);
            assertThrows(IllegalStateException.class, scheduler::tick);
            assertThrows(IllegalStateException.class, () -> scheduler.register(worker));
            scheduler.unregister(worker);
        }
    }

    @Test
    void mainPhasePrecedesIndependentWorkersWhichReallyOverlapAndJoin() {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        var bothEntered = new CountDownLatch(2);
        var completed = new CountDownLatch(2);
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                assertSame(owner, Thread.currentThread());
                log.add("main:first");
            }));
            for (String name : List.of("first", "second")) {
                scheduler.register(task(ExecutionMode.CONCURRENT, Set.of(), () -> {
                    assertNotSame(owner, Thread.currentThread());
                    threads.add(Thread.currentThread());
                    bothEntered.countDown();
                    await(bothEntered);
                    log.add("worker:" + name);
                    completed.countDown();
                }));
            }
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                assertEquals(List.of("main:first"), List.copyOf(log));
                log.add("main:second");
            }));

            scheduler.tick();

            assertEquals(0, completed.getCount());
            assertEquals(List.of("main:first", "main:second"), List.copyOf(log).subList(0, 2));
            assertEquals(4, log.size());
            assertEquals(2, Set.copyOf(threads).size());
        }
    }

    @Test
    void transitiveAffinityRunsInRegistrationOrderOnTheSameStableLane() {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        var first = task(ExecutionMode.CONCURRENT, Set.of("left"), () -> record(log, threads, "first"));
        var second = task(ExecutionMode.CONCURRENT, Set.of("right"), () -> record(log, threads, "second"));
        var bridge = task(ExecutionMode.CONCURRENT, new LinkedHashSet<>(List.of("left", "right")),
            () -> record(log, threads, "bridge"));
        try (var scheduler = new TaskScheduler(owner, 3, 4)) {
            scheduler.register(first);
            scheduler.register(second);
            scheduler.register(bridge);
            scheduler.register(first);
            scheduler.tick();
            bridge.keys = new LinkedHashSet<>(List.of("right", "left"));
            scheduler.tick();

            assertEquals(List.of("first", "second", "bridge", "first", "second", "bridge"), List.copyOf(log));
            assertEquals(1, Set.copyOf(threads).size());
            assertNotSame(owner, threads.element());
            scheduler.unregister(second);
            log.clear();
            scheduler.tick();
            assertEquals(List.of("first", "bridge"), List.copyOf(log));
        }
    }

    @Test
    void mainChangesConcurrentActivityBeforeOwnerCapturesWorkerMetadata() {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        var metadata = new ArrayList<String>();
        Task later = new Task(ExecutionMode.CONCURRENT, Set.of("source"), () -> {
            assertNotSame(owner, Thread.currentThread());
            log.add("later");
        }) {
            @Override
            public boolean active() {
                assertSame(owner, Thread.currentThread());
                metadata.add("active");
                return super.active();
            }

            @Override
            public ExecutionMode executionMode() {
                assertSame(owner, Thread.currentThread());
                metadata.add("mode");
                return super.executionMode();
            }

            @Override
            public Set<?> affinityKeys() {
                assertSame(owner, Thread.currentThread());
                metadata.add("keys");
                return super.affinityKeys();
            }
        };
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                later.enabled = !later.enabled;
                metadata.add("main");
                log.add("main");
            }));
            scheduler.register(later);
            metadata.clear();
            scheduler.tick();
            assertFalse(metadata.contains("keys"));
            assertEquals(List.of("main"), List.copyOf(log));
            metadata.clear();
            scheduler.tick();
            assertTrue(metadata.indexOf("keys") > metadata.indexOf("main"));
            assertEquals(List.of("main", "main", "later"), List.copyOf(log));
        }
    }

    @Test
    void mainAffinityChangesGroupTheCurrentTickBeforeWorkerDispatch() {
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        var first = task(ExecutionMode.CONCURRENT, Set.of(), () -> record(log, threads, "first"));
        var second = task(ExecutionMode.CONCURRENT, Set.of(), () -> record(log, threads, "second"));
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            scheduler.register(first);
            scheduler.register(second);
            scheduler.submit(() -> {
                first.keys = Set.of("new-shared-source");
                second.keys = Set.of("new-shared-source");
                log.add("main");
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(List.of("main", "first", "second"), List.copyOf(log));
            assertEquals(1, Set.copyOf(threads).size());
            assertNotSame(Thread.currentThread(), threads.element());
        }
    }

    @Test
    void frozenMainRegistrationsRejectRebirthAndExecutedMainCannotRunAgainAsConcurrent() {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        var oldMain = task(ExecutionMode.MAIN_THREAD, Set.of(), () -> log.add("old:main"));
        var newMain = task(ExecutionMode.MAIN_THREAD, Set.of(), () -> log.add("new:main"));
        var newWorker = task(ExecutionMode.CONCURRENT, Set.of(), () -> log.add("new:worker"));
        Task[] first = {null};
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            first[0] = task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                if (Thread.currentThread() != owner) {
                    log.add("first:worker");
                    return;
                }
                log.add("first:main");
                scheduler.unregister(oldMain);
                scheduler.register(oldMain);
                scheduler.unregister(first[0]);
                first[0].mode = ExecutionMode.CONCURRENT;
                scheduler.register(first[0]);
                scheduler.register(newMain);
                scheduler.register(newWorker);
                assertEquals(4, scheduler.registeredCount());
            });
            scheduler.register(first[0]);
            scheduler.register(oldMain);
            scheduler.tick();
            assertEquals(List.of("first:main", "new:worker"), List.copyOf(log));
            scheduler.tick();
            var result = List.copyOf(log);
            assertEquals(List.of("first:main", "new:worker", "old:main", "new:main"), result.subList(0, 4));
            assertEquals(Set.of("first:worker", "new:worker"), Set.copyOf(result.subList(4, 6)));
            assertEquals(6, result.size());
        }
    }

    @Test
    void metadataAndWorkersCannotMutateRegistrationsWhileMainCanReadCount() {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(owner, 1, 1)) {
            var other = task(ExecutionMode.CONCURRENT, Set.of(), () -> fail("Forbidden registration executed"));
            var guarded = new Task(ExecutionMode.CONCURRENT, Set.of("source"), () -> {
                assertNotSame(owner, Thread.currentThread());
                assertThrows(IllegalStateException.class, () -> scheduler.register(other));
                assertThrows(IllegalStateException.class, () -> scheduler.unregister(other));
                log.add("worker");
            }) {
                @Override
                public ExecutionMode executionMode() {
                    forbidMutation();
                    return super.executionMode();
                }

                @Override
                public boolean active() {
                    forbidMutation();
                    return super.active();
                }

                @Override
                public Set<?> affinityKeys() {
                    forbidMutation();
                    return super.affinityKeys();
                }

                private void forbidMutation() {
                    assertSame(owner, Thread.currentThread());
                    assertThrows(IllegalStateException.class, () -> scheduler.register(other));
                    assertThrows(IllegalStateException.class, () -> scheduler.unregister(other));
                }
            };
            scheduler.register(guarded);
            scheduler.submit(() -> {
                assertEquals(1, scheduler.registeredCount());
                log.add("main");
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(List.of("main", "worker"), List.copyOf(log));
        }
    }

    @Test
    void foreignAndRecursiveSubmissionsUseOneCapturedBatchPerTick() throws Exception {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(owner, 2, 2); var sender = auxiliary()) {
            sender.submit(() -> scheduler.submit(() -> {
                assertSame(owner, Thread.currentThread());
                log.add("outer:main");
                scheduler.submit(() -> log.add("next:main"), ExecutionMode.MAIN_THREAD);
            }, ExecutionMode.MAIN_THREAD)).get(5, TimeUnit.SECONDS);
            scheduler.submit(() -> {
                assertNotSame(owner, Thread.currentThread());
                log.add("outer:worker");
                scheduler.submit(() -> {
                    assertNotSame(owner, Thread.currentThread());
                    log.add("next:worker");
                }, ExecutionMode.CONCURRENT);
            }, ExecutionMode.CONCURRENT);

            scheduler.tick();
            assertEquals(List.of("outer:main", "outer:worker"), List.copyOf(log));
            scheduler.tick();
            assertEquals(List.of("outer:main", "outer:worker", "next:main", "next:worker"), List.copyOf(log));
            scheduler.tick();
            assertEquals(4, log.size());
        }
    }

    @Test
    void groupFailureStopsThatGroupButJoinsOtherAcceptedWorkBeforeFaulting() throws Exception {
        Thread owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var log = new ConcurrentLinkedQueue<String>();
        var primary = new IllegalStateException("group failed");
        var failed = task(ExecutionMode.CONCURRENT, Set.of("shared"), () -> {
            await(entered);
            throw primary;
        });
        try (var scheduler = new TaskScheduler(owner, 2, 2); var observer = auxiliary()) {
            scheduler.register(failed);
            scheduler.register(task(ExecutionMode.CONCURRENT, Set.of("shared"), () -> log.add("forbidden")));
            scheduler.register(task(ExecutionMode.CONCURRENT, Set.of(), () -> {
                entered.countDown();
                await(release);
                log.add("finished");
                finished.countDown();
            }));
            var observation = observer.submit(() -> holdUntilChecked(entered, returned, release));
            try {
                assertSame(primary, assertThrows(IllegalStateException.class, scheduler::tick));
                assertEquals(0, finished.getCount());
                returned.countDown();
                observation.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("finished"), List.copyOf(log));
                assertThrows(IllegalStateException.class, () -> scheduler.register(failed));
                assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> {
                }, ExecutionMode.MAIN_THREAD));
                assertThrows(IllegalStateException.class, scheduler::tick);
                scheduler.unregister(failed);
                assertEquals(2, scheduler.registeredCount());
            } finally {
                returned.countDown();
                release.countDown();
            }
        }
    }

    @Test
    void independentAcceptedWorkerFailuresAreAggregatedOnlyAfterBothExit() {
        var entered = new CountDownLatch(2);
        var exited = new CountDownLatch(2);
        var primary = new IllegalStateException("first worker failed");
        var secondary = new IllegalArgumentException("second worker failed");
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1)) {
            for (RuntimeException failure : List.of(primary, secondary)) {
                scheduler.submit(() -> {
                    entered.countDown();
                    await(entered);
                    exited.countDown();
                    throw failure;
                }, ExecutionMode.CONCURRENT);
            }
            assertSame(primary, assertThrows(IllegalStateException.class, scheduler::tick));
            assertEquals(0, exited.getCount());
            assertArrayEquals(new Throwable[]{secondary}, primary.getSuppressed());
            assertThrows(IllegalStateException.class, scheduler::tick);
        }
    }

    @Test
    void rejectedSecondDispatchStillDrainsFirstAcceptedWorker() throws Exception {
        Thread owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var rejected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var rejecting = new RejectSecondExecutor(rejected);
        try (var lane = auxiliary(); var scheduler = new TaskScheduler(owner, 2, 1, rejecting,
            new ExecutorService[]{lane}); var observer = auxiliary()) {
            scheduler.submit(() -> {
                entered.countDown();
                await(release);
                finished.countDown();
            }, ExecutionMode.CONCURRENT);
            scheduler.submit(() -> fail("Rejected work must never execute"), ExecutionMode.CONCURRENT);
            var observation = observer.submit(() -> {
                await(rejected);
                holdUntilChecked(entered, returned, release);
            });
            try {
                assertSame(rejecting.failure, assertThrows(RejectedExecutionException.class, scheduler::tick));
                assertEquals(0, finished.getCount());
                returned.countDown();
                observation.get(5, TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class, scheduler::tick);
                assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> {
                }, ExecutionMode.CONCURRENT));
            } finally {
                returned.countDown();
                release.countDown();
            }
        }
        assertTrue(rejecting.isTerminated());
    }

    @Test
    void interruptionDuringJoinDrainsWorkerRestoresFlagAndRevokesAdmission() throws Exception {
        Thread owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        try (var scheduler = new TaskScheduler(owner, 1, 1); var observer = auxiliary()) {
            scheduler.submit(() -> {
                entered.countDown();
                await(release);
                finished.countDown();
            }, ExecutionMode.CONCURRENT);
            var observation = observer.submit(() -> {
                await(entered);
                owner.interrupt();
                holdUntilChecked(entered, returned, release);
            });
            try {
                var failure = assertThrows(IllegalStateException.class, scheduler::tick);
                assertInstanceOf(InterruptedException.class, failure.getCause());
                assertTrue(owner.isInterrupted());
                assertEquals(0, finished.getCount());
                returned.countDown();
                assertTrue(Thread.interrupted(), "Consume the interrupt flag restored by the scheduler");
                observation.get(5, TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class, scheduler::tick);
                assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> {
                }, ExecutionMode.CONCURRENT));
            } finally {
                clearTestThreadInterrupt();
                returned.countDown();
                release.countDown();
            }
        }
    }

    @Test
    void ownerAndReentryChecksProtectBoundariesWhileCloseCannotResurrectTasks() throws Exception {
        Thread owner = Thread.currentThread();
        var log = new ConcurrentLinkedQueue<String>();
        var scheduler = new TaskScheduler(owner, 1, 1);
        try (scheduler; var foreign = auxiliary()) {
            var callback = task(ExecutionMode.MAIN_THREAD, Set.of(), () -> {
                assertThrows(IllegalStateException.class, scheduler::tick);
                assertThrows(IllegalStateException.class, scheduler::close);
                assertEquals(1, scheduler.registeredCount());
                log.add("ran");
            });
            scheduler.register(callback);
            foreign.submit(() -> {
                assertThrows(IllegalStateException.class, () -> scheduler.register(callback));
                assertThrows(IllegalStateException.class, () -> scheduler.unregister(callback));
                assertThrows(IllegalStateException.class, scheduler::registeredCount);
                assertThrows(IllegalStateException.class, scheduler::tick);
                assertThrows(IllegalStateException.class, scheduler::close);
            }).get(5, TimeUnit.SECONDS);
            scheduler.tick();
            scheduler.unregister(callback);
            scheduler.submit(() -> log.add("must be discarded"), ExecutionMode.MAIN_THREAD);
            scheduler.close();
            scheduler.close();
            assertThrows(IllegalStateException.class, scheduler::tick);
            assertThrows(IllegalStateException.class, () -> scheduler.register(callback));
            assertThrows(IllegalStateException.class, () -> scheduler.submit(() -> {
            }, ExecutionMode.MAIN_THREAD));
            assertEquals(List.of("ran"), List.copyOf(log));
        }
    }

    @Test
    void plannedModeIsRejectedAndAnInvalidChangedModeFaultsBeforeAnyDispatch() {
        var log = new ConcurrentLinkedQueue<String>();
        var changed = task(ExecutionMode.CONCURRENT, Set.of(), () -> log.add("must not run"));
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            assertThrows(IllegalArgumentException.class,
                () -> scheduler.register(task(ExecutionMode.PLANNED_PARALLEL, Set.of(), () -> log.add("planned"))));
            assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(() -> log.add("planned"), ExecutionMode.PLANNED_PARALLEL));
            scheduler.register(changed);
            scheduler.submit(() -> log.add("must not run"), ExecutionMode.MAIN_THREAD);
            changed.mode = ExecutionMode.PLANNED_PARALLEL;
            assertThrows(IllegalArgumentException.class, scheduler::tick);
            assertTrue(log.isEmpty());
            scheduler.unregister(changed);
            assertThrows(IllegalStateException.class, scheduler::tick);
        }
    }

    private static class Task implements ScheduledTask {
        private final Runnable action;
        private ExecutionMode mode;
        private Set<?> keys;
        private boolean enabled = true;

        private Task(ExecutionMode mode, Set<?> keys, Runnable action) {
            this.mode = mode;
            this.keys = keys;
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
            return enabled;
        }

        @Override
        public Set<?> affinityKeys() {
            return keys;
        }
    }

    private static final class RejectSecondExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        private final CountDownLatch rejected;
        private final RejectedExecutionException failure = new RejectedExecutionException("second dispatch rejected");
        private int executions;

        private RejectSecondExecutor(CountDownLatch rejected) {
            this.rejected = rejected;
        }

        @Override
        public void execute(@NotNull Runnable command) {
            if (++executions == 2) {
                rejected.countDown();
                throw failure;
            }
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
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
