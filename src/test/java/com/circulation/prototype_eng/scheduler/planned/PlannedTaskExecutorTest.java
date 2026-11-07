package com.circulation.prototype_eng.scheduler.planned;

import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Batch;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Failure;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Job;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.SourceJob;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Stopped;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Success;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlannedTaskExecutorTest {
    @Test void prepareActionsReallyOverlapAndResultsRetainInputOrder() {
        var bothEntered = new CountDownLatch(2);
        var secondReturned = new CountDownLatch(1);
        var threads = new ConcurrentLinkedQueue<Thread>();
        Thread owner = Thread.currentThread();
        try (var workers = Executors.newFixedThreadPool(2); var lane = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[] {lane});
            try {
                var batch = executor.runPrepare(List.of(new Job<>(null, () -> {
                    threads.add(Thread.currentThread());
                    bothEntered.countDown();
                    await(bothEntered);
                    await(secondReturned);
                    return "first";
                }), new Job<>(null, () -> {
                    threads.add(Thread.currentThread());
                    bothEntered.countDown();
                    await(bothEntered);
                    secondReturned.countDown();
                    return "second";
                })), deadline());

                assertEquals(List.of(new Success<>("first"), new Success<>("second")), batch.results());
                assertEquals(2, Set.copyOf(threads).size());
                assertFalse(threads.contains(owner));
                assertNull(batch.stopReason());
                batch.awaitQuiescence();
                assertTrue(batch.quiescent());
                assertFalse(workers.isShutdown(), "The runner only borrows its executors");
            } finally {
                bothEntered.countDown();
                bothEntered.countDown();
                secondReturned.countDown();
            }
        }
    }

    @Test void timeoutRetainsSuccessAndPinsAnUncooperativeLateActionAcrossClockWraparound() throws Exception {
        var firstExited = new CountDownLatch(1);
        var lateEntered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lateExited = new CountDownLatch(1);
        long start = Long.MAX_VALUE - 100L;
        long budget = TimeUnit.MILLISECONDS.toNanos(200L);
        var clock = new Clock(start);
        try (var workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override protected void afterExecute(Runnable task, Throwable failure) { firstExited.countDown(); }
        }; var lane = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[] {lane}, () -> clock.now);
            var running = new FutureTask<>(() -> executor.runPrepare(List.of(
                new Job<>(null, () -> "accepted"), new Job<>(null, () -> {
                    await(firstExited);
                    lateEntered.countDown();
                    clock.now = start + budget;
                    try {
                        awaitIgnoringInterrupt(release, interrupted);
                        return "late success";
                    } finally { lateExited.countDown(); }
                })), new PlannedDeadline(start, budget)));
            Thread.ofPlatform().daemon().start(running);
            try {
                await(lateEntered);
                Batch<String> batch = running.get(5L, TimeUnit.SECONDS);
                assertEquals(List.of(new Success<>("accepted"), new Stopped<>(Reason.WORKER_TIMEOUT, true)), batch.results());
                assertEquals(Reason.WORKER_TIMEOUT, batch.stopReason());
                await(interrupted);
                assertFalse(batch.quiescent());
                assertEquals(1L, lateExited.getCount());
                release.countDown();
                batch.awaitQuiescence();
                assertTrue(batch.quiescent());
                assertEquals(0L, lateExited.getCount());
                assertEquals(new Stopped<>(Reason.WORKER_TIMEOUT, true), batch.results().get(1));
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test void cancellationMakesQueuedAndNotYetSubmittedActionsPermanentlyInert() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var forbiddenCalls = new int[1];
        var token = new PlannedCancellationToken();
        try (var workers = Executors.newSingleThreadExecutor(); var lane = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[] {lane});
            var running = new FutureTask<>(() -> executor.runPrepare(List.of(new Job<>(token, () -> {
                entered.countDown();
                awaitIgnoringInterrupt(release, interrupted);
                return "late";
            }), new Job<>(token, () -> {
                forbiddenCalls[0]++;
                return "queued";
            }), new Job<>(token, () -> {
                forbiddenCalls[0]++;
                return "last";
            })), deadline()));
            Thread.ofPlatform().daemon().start(running);
            try {
                await(entered);
                assertTrue(token.cancel(Reason.MACHINE_UNLOAD));
                var batch = running.get(5L, TimeUnit.SECONDS);
                assertEquals(List.of(new Stopped<>(Reason.MACHINE_UNLOAD, true),
                    new Stopped<>(Reason.MACHINE_UNLOAD, false), new Stopped<>(Reason.MACHINE_UNLOAD, false)), batch.results());
                await(interrupted);
                assertFalse(batch.quiescent());
                release.countDown();
                batch.awaitQuiescence();
                workers.submit(() -> {}).get(5L, TimeUnit.SECONDS);
                assertEquals(0, forbiddenCalls[0], "Stopped wrappers must stay inert even after reaching the worker");
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test void sourceQueryAndCompensationUseTheOriginalLaneAfterTheLateActionActuallyExits() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queryEntered = new CountDownLatch(1);
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        String affinity = "source:world:machine:storage";
        try (var workers = Executors.newFixedThreadPool(2);
             var firstLane = Executors.newSingleThreadExecutor();
             var secondLane = Executors.newSingleThreadExecutor();
             var thirdLane = Executors.newSingleThreadExecutor()) {
            ExecutorService[] lanes = {firstLane, secondLane, thirdLane};
            var executor = new PlannedTaskExecutor(workers, lanes);
            var normal = executor.runOnSourceLanes(List.of(new SourceJob<>(affinity, new Job<>(null, () -> {
                threads.add(Thread.currentThread());
                log.add("normal");
                return "normal";
            }))), deadline());
            normal.awaitQuiescence();
            assertEquals(List.of(new Success<>("normal")), normal.results());
            var old = new FutureTask<>(() -> executor.runOnSourceLanes(List.of(new SourceJob<>(affinity, new Job<>(null, () -> {
                threads.add(Thread.currentThread());
                log.add("old entered");
                entered.countDown();
                awaitIgnoringInterrupt(release, interrupted);
                log.add("old exited");
                return "late";
            }))), PlannedDeadline.after(Duration.ofMillis(300L))));
            Thread.ofPlatform().daemon().start(old);
            try {
                await(entered);
                var stopped = old.get(5L, TimeUnit.SECONDS);
                assertEquals(List.of(new Stopped<>(Reason.WORKER_TIMEOUT, true)), stopped.results());
                await(interrupted);
                assertFalse(stopped.quiescent());
                var followup = new FutureTask<>(() -> new PlannedTaskExecutor(workers, lanes).runOnSourceLanes(List.of(
                    new SourceJob<>(affinity, new Job<>(null, () -> {
                        threads.add(Thread.currentThread());
                        log.add("query");
                        queryEntered.countDown();
                        return "committed";
                    })), new SourceJob<>(affinity, new Job<>(null, () -> {
                        threads.add(Thread.currentThread());
                        log.add("compensate");
                        return "rolled back";
                    }))), deadline()));
                Thread.ofPlatform().daemon().start(followup);
                assertFalse(queryEntered.await(100L, TimeUnit.MILLISECONDS), "A timed-out action still owns its physical source lane");
                release.countDown();
                var recovered = followup.get(5L, TimeUnit.SECONDS);
                recovered.awaitQuiescence();
                stopped.awaitQuiescence();
                assertEquals(List.of(new Success<>("committed"), new Success<>("rolled back")), recovered.results());
                assertEquals(List.of("normal", "old entered", "old exited", "query", "compensate"), List.copyOf(log));
                assertEquals(1, Set.copyOf(threads).size());
                assertEquals(new Stopped<>(Reason.WORKER_TIMEOUT, true), stopped.results().getFirst());
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test void executorRejectionIsAnOrderedFailureAndDoesNotAbandonOtherAcceptedActions() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var thirdFinished = new CountDownLatch(1);
        var rejection = new RejectedExecutionException("second planned submission");
        try (var workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            private int submissions;
            @Override public void execute(@NotNull Runnable action) {
                if (++submissions == 2) { throw rejection; }
                super.execute(action);
            }
        }; var lane = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[] {lane});
            var running = new FutureTask<>(() -> executor.runPrepare(List.of(new Job<>(null, () -> {
                entered.countDown();
                await(release);
                return "first";
            }), new Job<>(null, () -> fail("Rejected action executed")), new Job<>(null, () -> {
                thirdFinished.countDown();
                return "third";
            })), deadline()));
            Thread.ofPlatform().daemon().start(running);
            try {
                await(entered);
                await(thirdFinished);
                assertFalse(running.isDone(), "Accepted first action must still be joined after a later rejection");
                release.countDown();
                var batch = running.get(5L, TimeUnit.SECONDS);
                batch.awaitQuiescence();
                assertEquals(List.of(new Success<>("first"), new Failure<>(rejection), new Success<>("third")), batch.results());
                assertNull(batch.stopReason());
                assertTrue(batch.quiescent());
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test void interruptedOwnerKeepsItsFlagAndInterruptedQuiescenceWaitStillPinsTheAction() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerInterrupted = new CountDownLatch(1);
        var waiterEntered = new CountDownLatch(1);
        var waiterReturned = new CountDownLatch(1);
        var ownerFlag = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor(); var lane = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[] {lane});
            var running = new FutureTask<>(() -> {
                var batch = executor.runPrepare(List.of(new Job<>(null, () -> {
                    entered.countDown();
                    awaitIgnoringInterrupt(release, workerInterrupted);
                    return "late";
                })), deadline());
                if (Thread.currentThread().isInterrupted()) { ownerFlag.countDown(); }
                return batch;
            });
            Thread owner = Thread.ofPlatform().daemon().start(running);
            try {
                await(entered);
                owner.interrupt();
                var batch = running.get(5L, TimeUnit.SECONDS);
                assertEquals(Reason.INTERRUPTED, batch.stopReason());
                assertEquals(List.of(new Stopped<>(Reason.INTERRUPTED, true)), batch.results());
                await(ownerFlag);
                await(workerInterrupted);
                assertFalse(batch.quiescent());
                var waiting = new FutureTask<>(() -> {
                    Thread.currentThread().interrupt();
                    waiterEntered.countDown();
                    try {
                        var failure = assertThrows(IllegalStateException.class, batch::awaitQuiescence);
                        assertInstanceOf(InterruptedException.class, failure.getCause());
                        return Thread.currentThread().isInterrupted();
                    } finally { waiterReturned.countDown(); }
                });
                Thread.ofPlatform().daemon().start(waiting);
                await(waiterEntered);
                assertFalse(waiterReturned.await(100L, TimeUnit.MILLISECONDS), "An interrupted exit waiter must keep the running action pinned");
                release.countDown();
                assertTrue(waiting.get(5L, TimeUnit.SECONDS));
                assertTrue(batch.quiescent());
                assertEquals(new Stopped<>(Reason.INTERRUPTED, true), batch.results().getFirst());
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test void deadlineIsRecheckedAtActionEntryAndResultPublicationWithoutRelyingOnOwnerWakeup() {
        var clock = new Clock(-100);
        try (var workers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override public void execute(@NotNull Runnable action) {
                clock.now = -90;
                action.run();
            }
        }; var lane = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[] {lane}, () -> clock.now);
            var queued = executor.runPrepare(List.of(new Job<String>(null,
                () -> fail("A queued action entered after its deadline"))), new PlannedDeadline(-100, 10));
            assertEquals(List.of(new Stopped<>(Reason.WORKER_TIMEOUT, false)), queued.results());
            assertTrue(queued.quiescent());
        }

        long start = Long.MAX_VALUE - 3;
        long budget = TimeUnit.SECONDS.toNanos(5);
        clock.now = start;
        try (var workers = Executors.newSingleThreadExecutor(); var lane = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[] {lane}, () -> clock.now);
            var late = executor.runPrepare(List.of(new Job<>(null, () -> {
                clock.now = start + budget;
                return "past deadline";
            })), new PlannedDeadline(start, budget));
            late.awaitQuiescence();
            assertEquals(List.of(new Stopped<>(Reason.WORKER_TIMEOUT, true)), late.results());
        }
    }

    private static final class Clock {
        private volatile long now;
        private Clock(long now) { this.now = now; }
    }

    private static PlannedDeadline deadline() { return PlannedDeadline.after(Duration.ofSeconds(5L)); }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5L, TimeUnit.SECONDS), "Test coordination did not complete"); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Unexpected test action interruption", failure);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release, CountDownLatch interrupted) {
        boolean done = false;
        while (!done) {
            try {
                assertTrue(release.await(5L, TimeUnit.SECONDS), "Test must release its uncooperative action");
                done = true;
            } catch (InterruptedException failure) { interrupted.countDown(); }
        }
    }
}
