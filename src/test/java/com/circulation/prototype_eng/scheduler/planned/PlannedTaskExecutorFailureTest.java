package com.circulation.prototype_eng.scheduler.planned;

import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Batch;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Failure;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Job;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.SourceJob;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Stopped;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor.Success;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedTaskExecutorFailureTest {
    @Test
    void coordinatorReceivesTheExactBatchBeforeEvenAnInlineSourceCanExecute() {
        var events = new ObjectArrayList<String>();
        var retained = new ObjectArrayList<Batch<String>>();
        try (var workers = Executors.newSingleThreadExecutor(); var lane = inlineLane()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[]{lane}, () -> 0);
            var batch = executor.runOnSourceLanes(List.of(new SourceJob<>("source", new Job<>(null, () -> {
                assertEquals(1, retained.size());
                events.add("source");
                return "applied";
            }))), deadline(), exact -> {
                retained.add(exact);
                events.add("captured");
            });
            assertSame(retained.getFirst(), batch);
            assertEquals(List.of("captured", "source"), events);
            assertEquals(List.of(new Success<>("applied")), batch.results());
            batch.awaitQuiescence();
        }
    }

    @Test
    void callbackFailureLeavesACompleteJoinableBatchWithoutDispatchingAnyAction() {
        var retained = new ObjectArrayList<Batch<String>>();
        var primary = new IllegalStateException("coordinator capture failed");
        int[] actions = {0};
        try (var workers = Executors.newSingleThreadExecutor(); var lane = inlineLane()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[]{lane}, () -> 0);
            var job = new SourceJob<>("source", new Job<>(null, () -> { actions[0]++; return "forbidden"; }));
            assertSame(primary, assertThrows(IllegalStateException.class,
                () -> executor.runOnSourceLanes(List.of(job, job), deadline(), batch -> {
                    retained.add(batch);
                    throw primary;
                })));
            var batch = retained.getFirst();
            assertEquals(List.of(new Stopped<>(Reason.WORKER_FAILURE, false), new Stopped<>(Reason.WORKER_FAILURE, false)), batch.results());
            batch.awaitQuiescence();
            assertTrue(batch.quiescent());
            assertEquals(0, actions[0]);
        }
    }

    @Test
    void directRunnerClockFailurePinsRunningWorkAndMakesQueuedAndNewActionsInert() throws Exception {
        var owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var retained = new ObjectArrayList<Batch<String>>();
        var primary = new IllegalStateException("third acceptance clock failed");
        int[] ownerReads = {0};
        int[] actions = {0, 0, 0};
        @SuppressWarnings("resource")
        var lane = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            Thread.ofPlatform().daemon().factory()) {
            private int submissions;
            @Override public void execute(@NotNull Runnable action) {
                super.execute(action);
                if (++submissions == 1) { await(entered); }
            }
        };
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor = new PlannedTaskExecutor(workers, new ExecutorService[]{lane}, () -> {
                if (Thread.currentThread() == owner && ++ownerReads[0] == 3) { throw primary; }
                return 0;
            });
            var jobs = List.of(new SourceJob<>("source", new Job<>(null, () -> {
                actions[0]++;
                entered.countDown();
                awaitIgnoringInterrupt(release, interrupted);
                return "late";
            })), new SourceJob<>("source", new Job<>(null, () -> { actions[1]++; return "queued"; })),
                new SourceJob<>("source", new Job<>(null, () -> { actions[2]++; return "new"; })));
            try {
                assertSame(primary, assertThrows(IllegalStateException.class,
                    () -> executor.runOnSourceLanes(jobs, deadline(), retained::add)));
                var batch = retained.getFirst();
                assertEquals(List.of(new Stopped<>(Reason.WORKER_FAILURE, true), new Stopped<>(Reason.WORKER_FAILURE, false),
                    new Stopped<>(Reason.WORKER_FAILURE, false)), batch.results());
                await(interrupted);
                assertFalse(batch.quiescent());
                release.countDown();
                batch.awaitQuiescence();
                lane.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertArrayEquals(new int[]{1, 0, 0}, actions);
                assertEquals(new Stopped<>(Reason.WORKER_FAILURE, true), batch.results().getFirst());
            } finally { release.countDown(); }
        } finally {
            release.countDown();
            lane.shutdownNow();
            assertTrue(lane.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void wrapperEntryAndPublicationClockFaultsAlwaysPublishAndProveExitWithoutReplayingAction() {
        for (int failedRead : new int[]{2, 3}) {
            for (boolean businessFails : new boolean[]{false, true}) {
                var clockFault = new AssertionError("wrapper clock failed");
                var businessFault = new IllegalStateException("business failed");
                int[] clockReads = {0};
                int[] actions = {0};
                try (var workers = Executors.newSingleThreadExecutor(); var lane = inlineLane()) {
                    var executor = new PlannedTaskExecutor(workers, new ExecutorService[]{lane}, () -> {
                        if (++clockReads[0] == failedRead) { throw clockFault; }
                        return 0;
                    });
                    var batch = executor.runOnSourceLanes(List.of(new SourceJob<>("source", new Job<>(null, () -> {
                        actions[0]++;
                        if (businessFails) { throw businessFault; }
                        return "applied";
                    }))), deadline());
                    Throwable expected = failedRead == 3 && businessFails ? businessFault : clockFault;
                    assertEquals(List.of(new Failure<>(expected)), batch.results());
                    batch.awaitQuiescence();
                    assertTrue(batch.quiescent());
                    assertEquals(failedRead == 2 ? 0 : 1, actions[0]);
                    if (expected == businessFault) { assertArrayEquals(new Throwable[]{clockFault}, businessFault.getSuppressed()); }
                }
            }
        }
    }

    private static ThreadPoolExecutor inlineLane() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override public void execute(@NotNull Runnable action) { action.run(); }
        };
    }

    private static PlannedDeadline deadline() { return new PlannedDeadline(0, TimeUnit.SECONDS.toNanos(5)); }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release, CountDownLatch interrupted) {
        boolean done = false;
        while (!done) {
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); done = true; }
            catch (InterruptedException ignored) { interrupted.countDown(); }
        }
    }
}
