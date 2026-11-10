package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureWorkerBatchTest {
    private static TaskScheduler.StructureWork work(String name, ConcurrentLinkedQueue<String> log) {
        return new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(5)), () -> {
            log.add(name + "-read");
            return true;
        }, result -> {
            assertInstanceOf(PlannedTaskExecutor.Success.class, result);
            log.add(name + "-owner");
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for structure workers");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void runningShortDeadlineInterruptsIndependentlyWhileTheOwnerStillWaitsForItsExit() throws Exception {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            var entered = new CountDownLatch(2);
            var shortInterrupted = new CountDownLatch(1);
            var releaseShort = new CountDownLatch(1);
            var shortExited = new CountDownLatch(1);
            var longFinished = new CountDownLatch(1);
            var completed = new AtomicInteger();
            scheduler.enqueueStructureCheck(() -> new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(1)), () -> {
                entered.countDown();
                await(entered);
                boolean waiting = true;
                while (waiting) {
                    try {
                        releaseShort.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        shortInterrupted.countDown();
                    }
                }
                shortExited.countDown();
                return true;
            }, result -> {
                var stopped = assertInstanceOf(PlannedTaskExecutor.Stopped.class, result);
                assertEquals(Reason.WORKER_TIMEOUT, stopped.reason());
                assertTrue(stopped.started());
                assertEquals(0, shortExited.getCount());
                completed.incrementAndGet();
                return null;
            }));
            scheduler.enqueueStructureCheck(() -> new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(10)), () -> {
                entered.countDown();
                await(shortInterrupted);
                longFinished.countDown();
                return true;
            }, result -> {
                assertEquals(Boolean.TRUE, assertInstanceOf(PlannedTaskExecutor.Success.class, result).value());
                assertEquals(0, shortExited.getCount());
                completed.incrementAndGet();
                return null;
            }));
            var observed = observer.submit(() -> {
                try {
                    await(shortInterrupted);
                    await(longFinished);
                    assertEquals(1, shortExited.getCount());
                    assertEquals(0, completed.get());
                } finally {
                    releaseShort.countDown();
                }
            });
            try {
                scheduler.tick();
                observed.get(5, TimeUnit.SECONDS);
                assertEquals(2, completed.get());
            } finally {
                releaseShort.countDown();
            }
        }
    }

    @Test
    void pendingSourceConfirmationKeepsPeriodicCaptureQueued() {
        var confirmed = new AtomicBoolean();
        var offered = new AtomicBoolean();
        var exits = new AtomicInteger();
        var reads = new AtomicInteger();
        var pending = new PlannedExecution() {
            @Override
            public boolean advance(PlannedDeadline deadline) {
                return confirmed.get();
            }

            @Override
            public void cancel(Reason reason) {
            }

            @Override
            public void awaitExit() {
                exits.incrementAndGet();
            }

            @Override
            public boolean finished() {
                return confirmed.get();
            }
        };
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1,
            (_, _) -> offered.compareAndSet(false, true) ? pending : null, Duration.ofSeconds(1))) {
            scheduler.tick();
            scheduler.enqueueStructureCheck(() -> {
                assertTrue(confirmed.get());
                assertTrue(exits.get() > 0);
                return new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(5)),
                    () -> {
                        reads.incrementAndGet();
                        return true;
                    }, _ -> null);
            });
            scheduler.tick();
            assertEquals(0, reads.get());
            confirmed.set(true);
            scheduler.tick();
            assertEquals(1, reads.get());
        }
    }

    @Test
    void deadlineStopStillWaitsForAnUncooperativeReadersActualExit() throws Exception {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            var interrupted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var completed = new AtomicInteger();
            scheduler.enqueueStructureCheck(() -> new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(1)), () -> {
                boolean waiting = true;
                while (waiting) {
                    try {
                        release.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        interrupted.countDown();
                    }
                }
                exited.countDown();
                return true;
            }, result -> {
                assertInstanceOf(PlannedTaskExecutor.Stopped.class, result);
                assertEquals(0, exited.getCount());
                completed.incrementAndGet();
                return null;
            }));
            var observed = observer.submit(() -> {
                await(interrupted);
                try {
                    assertEquals(0, completed.get());
                } finally {
                    release.countDown();
                }
            });
            try {
                scheduler.tick();
                observed.get(5, TimeUnit.SECONDS);
                assertEquals(1, completed.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void anInterveningOwnerBoundarySeparatesPeriodicBatches() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1);
             var submitter = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            scheduler.enqueueStructureCheck(() -> work("first", log));
            submitter.submit(() -> scheduler.runAtBoundary(() -> log.add("reload"))).get(5, TimeUnit.SECONDS);
            scheduler.enqueueStructureCheck(() -> work("second", log));
            assertTrue(log.isEmpty());
            scheduler.tick();
            assertEquals(List.of("first-read", "first-owner", "reload", "second-read", "second-owner"), List.copyOf(log));
        }
    }

    @Test
    void anExpiredAttemptDoesNotSpendOrCancelAnotherHostsAllowance() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1)) {
            var staleCalls = new AtomicInteger();
            var liveCalls = new AtomicInteger();
            var callbacks = new AtomicInteger();
            scheduler.enqueueStructureCheck(() -> new TaskScheduler.StructureWork(
                new PlannedDeadline(System.nanoTime() - TimeUnit.SECONDS.toNanos(2), TimeUnit.SECONDS.toNanos(1)),
                () -> {
                    staleCalls.incrementAndGet();
                    return true;
                }, result -> {
                var stopped = assertInstanceOf(PlannedTaskExecutor.Stopped.class, result);
                assertEquals(Reason.WORKER_TIMEOUT, stopped.reason());
                assertFalse(stopped.started());
                callbacks.incrementAndGet();
                return null;
            }));
            scheduler.enqueueStructureCheck(() -> new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(5)),
                () -> {
                    liveCalls.incrementAndGet();
                    return true;
                }, result -> {
                assertEquals(Boolean.TRUE, assertInstanceOf(PlannedTaskExecutor.Success.class, result).value());
                callbacks.incrementAndGet();
                return null;
            }));
            scheduler.tick();
            assertEquals(0, staleCalls.get());
            assertEquals(1, liveCalls.get());
            assertEquals(2, callbacks.get());
        }
    }

    @Test
    void aFailedWorkerCannotRunOwnerCleanupUntilItsBatchPeerActuallyExits() throws Exception {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var completed = new AtomicInteger();
            var failure = new IllegalStateException("one structure failed");
            var closed = new AtomicInteger();
            scheduler.enqueueStructureCheck(() -> new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(5)), () -> {
                entered.countDown();
                await(entered);
                throw failure;
            }, _ -> {
                assertEquals(0, exited.getCount());
                closed.incrementAndGet();
            }, result -> {
                assertSame(failure, assertInstanceOf(PlannedTaskExecutor.Failure.class, result).failure());
                assertEquals(0, exited.getCount());
                assertEquals(2, closed.get());
                completed.incrementAndGet();
                throw failure;
            }));
            scheduler.enqueueStructureCheck(() -> new TaskScheduler.StructureWork(PlannedDeadline.after(Duration.ofSeconds(5)), () -> {
                entered.countDown();
                try {
                    await(release);
                    return true;
                } finally {
                    exited.countDown();
                }
            }, _ -> {
                assertEquals(0, exited.getCount());
                closed.incrementAndGet();
            }, result -> {
                assertEquals(0, exited.getCount());
                assertEquals(2, closed.get());
                assertEquals(Boolean.TRUE, assertInstanceOf(PlannedTaskExecutor.Success.class, result).value());
                completed.incrementAndGet();
                return null;
            }));
            var observed = observer.submit(() -> {
                await(entered);
                try {
                    assertEquals(0, completed.get());
                } finally {
                    release.countDown();
                }
            });
            try {
                assertSame(failure, assertThrows(IllegalStateException.class, scheduler::tick));
                observed.get(5, TimeUnit.SECONDS);
                assertEquals(2, completed.get());
            } finally {
                release.countDown();
            }
        }
    }
}
