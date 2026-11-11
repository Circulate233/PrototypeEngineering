package com.circulation.prototype_eng.scheduler;

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerReconfigurationTest {
    private static SchedulerConfig.Settings settings(int workers, int lanes, Duration timeout, int window) {
        return new SchedulerConfig.Settings(workers, lanes, timeout, false, 200, window);
    }

    private static ScheduledTask task(ExecutionMode mode, Set<?> keys, Runnable action) {
        return new ScheduledTask() {
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

            @Override
            public Set<?> affinityKeys() {
                return keys;
            }
        };
    }

    private static ExecutorService executor() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    @SuppressWarnings("resource")
        // Both injected executors transfer ownership to the scheduler under test.
    void retainedCleanupKeepsOriginalPoolsAndBudgetThenInstallsAfterBoundaryWithoutLosingWork() {
        var log = new ArrayList<String>();
        var oldWorkers = executor();
        var oldLane = executor();
        var retained = new Retained();
        var captures = new AtomicInteger();
        var timeouts = new LongArrayList();
        var next = settings(2, 2, Duration.ofMillis(1234), 10);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1, oldWorkers,
            new ExecutorService[]{oldLane}, (_, deadline) -> {
            timeouts.add(deadline.timeoutNanos());
            return captures.getAndIncrement() == 0 ? retained : null;
        }, Duration.ofSeconds(5))) {
            var runs = new AtomicInteger();
            scheduler.register(task(ExecutionMode.CONCURRENT, Set.of("source"), runs::incrementAndGet));
            scheduler.tick();
            int initialRuns = runs.get();
            assertNotNull(retained.cancelled);
            scheduler.requestSettings(next);
            scheduler.submit(() -> log.add("once-main"), ExecutionMode.MAIN_THREAD);
            scheduler.submit(runs::incrementAndGet, ExecutionMode.CONCURRENT);
            scheduler.runAtBoundary(() -> {
                assertFalse(oldWorkers.isShutdown());
                assertFalse(oldLane.isShutdown());
                log.add("old-boundary");
            });
            scheduler.tick();
            assertSame(next, scheduler.pendingSettings());
            assertEquals(1, scheduler.effectiveSettings().workerThreads());
            assertEquals(initialRuns, runs.get());
            assertTrue(log.isEmpty());
            assertFalse(oldWorkers.isShutdown());
            assertFalse(oldLane.isShutdown());
            retained.confirmed = true;
            scheduler.tick(() -> {
                assertTrue(oldWorkers.isTerminated());
                assertTrue(oldLane.isTerminated());
            });
            assertEquals(List.of("old-boundary", "once-main"), log);
            assertEquals(initialRuns + 2, runs.get());
            assertEquals(1, scheduler.registeredCount());
            assertSame(next, scheduler.effectiveSettings());
            assertNull(scheduler.pendingSettings());
            assertEquals(List.of(Duration.ofSeconds(5).toNanos(), next.plannedTimeout().toNanos()), timeouts);
            assertTrue(retained.cleanupBudgets.longStream().allMatch(value -> value == Duration.ofSeconds(5).toNanos()));
            assertTrue(retained.exitProofs > 0);
            scheduler.tick();
            assertEquals(initialRuns + 3, runs.get(), "One-shot work must not replay");
        }
    }

    @Test
    void requestDuringMainWaitsForNextTickAndKeepsDeferredSubmissions() {
        var initial = settings(1, 1, Duration.ofNanos(1234567), 8);
        var next = settings(2, 2, Duration.ofMillis(9), 3);
        var runs = new AtomicInteger();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), initial)) {
            scheduler.submit(() -> {
                scheduler.requestSettings(next);
                scheduler.submit(runs::incrementAndGet, ExecutionMode.MAIN_THREAD);
                scheduler.runAtBoundary(() -> assertSame(initial, scheduler.effectiveSettings()));
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertSame(initial, scheduler.effectiveSettings());
            assertSame(next, scheduler.pendingSettings());
            assertEquals(0, runs.get());
            scheduler.tick();
            assertSame(next, scheduler.effectiveSettings());
            assertEquals(1, runs.get());
            var report = scheduler.snapshotReport();
            assertEquals(2, report.completedTicks());
            assertEquals(1, report.sampleCount(), "Window resize resets only the ring");
            assertEquals(1, report.customMainTasks().max());
            scheduler.tick();
            assertEquals(1, runs.get());
        }
    }

    @Test
    void requestFromOldBoundarySupersedesFrozenRequestAndStillWaitsAnotherTick() {
        var first = settings(1, 1, Duration.ofSeconds(5), 4);
        var frozen = settings(2, 2, Duration.ofMillis(20), 4);
        var replacement = first.withMetricsEnabled(true);
        var retained = new Retained();
        var offered = new AtomicInteger();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), first,
            (_, _) -> offered.getAndIncrement() == 0 ? retained : null, null)) {
            scheduler.tick();
            scheduler.requestSettings(frozen);
            scheduler.runAtBoundary(() -> scheduler.requestSettings(replacement));
            retained.confirmed = true;
            scheduler.tick();
            assertSame(first, scheduler.effectiveSettings());
            assertSame(replacement, scheduler.pendingSettings());
            scheduler.tick();
            assertSame(replacement, scheduler.effectiveSettings());
            assertNull(scheduler.pendingSettings());
        }
    }

    @Test
    void ordinaryReportMeasuresActualJoinAndCountsAcceptedGroupsWhileLoggingIsDisabled() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var runs = new AtomicInteger();
        try (var releaser = executor(); var scheduler = new TaskScheduler(Thread.currentThread(), settings(2, 1, Duration.ofSeconds(5), 4))) {
            scheduler.register(task(ExecutionMode.MAIN_THREAD, Set.of(), runs::incrementAndGet));
            scheduler.register(task(ExecutionMode.CONCURRENT, Set.of("shared"), runs::incrementAndGet));
            scheduler.register(task(ExecutionMode.CONCURRENT, Set.of("shared"), runs::incrementAndGet));
            scheduler.submit(runs::incrementAndGet, ExecutionMode.MAIN_THREAD);
            scheduler.submit(() -> {
                entered.countDown();
                await(release);
                runs.incrementAndGet();
            }, ExecutionMode.CONCURRENT);
            var observer = releaser.submit(() -> {
                await(entered);
                try {
                    assertFalse(release.await(30, TimeUnit.MILLISECONDS));
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                } finally {
                    release.countDown();
                }
            });
            scheduler.tick();
            observer.get(5, TimeUnit.SECONDS);
            var report = scheduler.snapshotReport();
            assertEquals(5, runs.get());
            assertEquals(1, report.mainTasks().max());
            assertEquals(2, report.concurrentTasks().max());
            assertEquals(1, report.customMainTasks().max());
            assertEquals(1, report.customConcurrentTasks().max());
            assertEquals(1, report.affinityGroups().max());
            assertTrue(report.joinWallNanos().max() > 0);
            assertTrue(report.concurrentStageWallNanos().max() >= report.joinWallNanos().max());
            assertTrue(report.totalWallNanos().max() >= report.concurrentStageWallNanos().max());
            assertFalse(scheduler.effectiveSettings().metricsEnabled());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failureRecordsOnceAndPhysicalStructureWorkerTimingIsReported() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), settings(1, 1, Duration.ofSeconds(5), 4))) {
            scheduler.submit(() -> scheduler.runAtBoundary(() -> scheduler.runStructureWorker(() -> 42,
                PlannedDeadline.after(Duration.ofSeconds(5)))), ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            var report = scheduler.snapshotReport();
            assertEquals(1, report.completedTicks());
            assertTrue(report.structureWorkerWallNanos().max() > 0);
            assertTrue(report.cleanupWallNanos().max() > 0);
            scheduler.submit(() -> {
                throw new IllegalStateException("expected");
            }, ExecutionMode.MAIN_THREAD);
            assertThrows(IllegalStateException.class, scheduler::tick);
            report = scheduler.snapshotReport();
            assertEquals(2, report.completedTicks());
            assertEquals(2, report.sampleCount());
            assertEquals(1, report.customMainTasks().max());
        }
    }

    @Test
    void metadataCannotQueueSettingsAndInvalidDurationCannotRetireExistingPools() {
        var initial = settings(1, 1, Duration.ofSeconds(5), 4);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), initial)) {
            scheduler.tick(() -> assertThrows(IllegalStateException.class, () -> scheduler.requestSettings(initial)));
            assertNull(scheduler.pendingSettings());
            assertThrows(ArithmeticException.class, () -> settings(2, 2, Duration.ofSeconds(Long.MAX_VALUE), 4));
            scheduler.submit(() -> {
            }, ExecutionMode.CONCURRENT);
            scheduler.tick();
            assertSame(initial, scheduler.effectiveSettings());
        }
    }

    private static final class Retained implements PlannedExecution {
        private final LongList cleanupBudgets = new LongArrayList();
        private boolean confirmed;
        private Reason cancelled;
        private int exitProofs;

        @Override
        public boolean advance(PlannedDeadline deadline) {
            cleanupBudgets.add(deadline.timeoutNanos());
            return confirmed;
        }

        @Override
        public void awaitExit() {
            exitProofs++;
        }

        @Override
        public boolean finished() {
            return confirmed;
        }

        @Override
        public void cancel(Reason reason) {
            if (cancelled == null) cancelled = reason;
        }
    }
}
