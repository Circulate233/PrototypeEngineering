package com.circulation.prototype_eng.storage;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StorageFenceBudgetTest {
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static boolean slice(StorageFence.Acquire acquisition) {
        boolean done = acquisition.advance(new RecipeWorkBudget(1)::take);
        // This entry rejects both a leaked scope and any physical lock on this creating thread.
        StorageFence.beginAcquire(List.of()).cancel();
        return done;
    }

    private static void release(StorageFence.Release ending) {
        int calls = 0;
        while (!ending.advance(new RecipeWorkBudget(1)::take)) {
            assertTrue(++calls < 10_000);
        }
    }

    private static int blocked(List<StorageContext> contexts) {
        int count = 0;
        for (var context : contexts) {
            if (context.tryOrdinaryLock()) {
                context.unlockDeferred();
            } else {
                count++;
            }
        }
        return count;
    }

    @Test
    void largeUnsortedIdentitySetClaimsAndReleasesWithOneUnitAndNoRetainedJvmScope() {
        var contexts = new ArrayList<StorageContext>();
        for (int index = 0; index < 129; index++) {
            contexts.add(new StorageContext(() -> {
            }));
        }
        var input = new ArrayList<StorageContext>();
        for (int index = contexts.size() - 1; index >= 0; index--) {
            input.add(contexts.get(index));
            input.add(contexts.get(index));
        }
        var claim = StorageFence.beginAcquire(input);
        int advances = 0, previous = 0;
        boolean done;
        do {
            done = slice(claim);
            int current = blocked(contexts);
            assertTrue(current - previous <= 1, "One unit installed several physical-domain barriers");
            previous = current;
            assertTrue(++advances < 10_000);
        } while (!done);
        assertTrue(advances > contexts.size());
        var fence = Objects.requireNonNull(claim.result());
        for (var context : contexts) {
            assertFalse(context.tryOrdinaryLock());
        }
        for (var context : contexts) {
            assertTrue(fence.tryAccessWithinBudget(() -> {
                context.lock();
                try {
                    context.changed();
                } finally {
                    context.unlockDeferred();
                }
            }));
        }
        release(fence.beginRelease());
        for (var context : contexts) {
            assertTrue(context.tryOrdinaryLock());
            try {
                assertEquals(1, context.revision());
            } finally {
                context.unlock();
            }
        }
    }

    @Test
    @SuppressWarnings("StatementWithEmptyBody")
        // The actual charged cursor advances in the condition.
    void cancellingAnInstalledPrefixEventuallyOpensItWithoutGrantingTheIncompleteFence() {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var claim = StorageFence.beginAcquire(List.of(second, first));
        boolean reachedPrefix = false;
        for (int index = 0; index < 100 && !reachedPrefix; index++) {
            assertFalse(slice(claim));
            reachedPrefix = !first.tryOrdinaryLock();
            if (!reachedPrefix) {
                first.unlockDeferred();
            }
        }
        assertTrue(reachedPrefix);
        assertTrue(second.tryOrdinaryLock());
        second.unlockDeferred();
        claim.cancel();
        while (!slice(claim)) {
        }
        assertNull(claim.result());
        var replacement = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
        replacement.release();
    }

    @Test
    @SuppressWarnings("StatementWithEmptyBody")
        // The actual charged cursor advances in the condition.
    void overlappingPendingClaimsRejectAndCleanOnlyTheLosingExactPrefix() {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var third = new StorageContext(() -> {
        });
        var winner = StorageFence.beginAcquire(List.of(third, second));
        for (int index = 0; index < 100; index++) {
            assertFalse(slice(winner));
            if (!second.tryOrdinaryLock()) {
                break;
            }
            second.unlockDeferred();
        }
        assertFalse(second.tryOrdinaryLock());
        var loser = StorageFence.beginAcquire(List.of(second, first));
        int calls = 0;
        while (!slice(loser)) {
            assertTrue(++calls < 1000);
        }
        assertNull(loser.result());
        assertTrue(first.tryOrdinaryLock());
        first.unlockDeferred();
        assertFalse(second.tryOrdinaryLock());
        winner.cancel();
        while (!slice(winner)) {
        }
        assertNull(winner.result());
        Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second, third))).release();
    }

    @Test
    void notificationFaultIsRememberedAcrossSlicesWhileEveryOtherNotificationIsAttemptedOnce() {
        var contexts = new ArrayList<StorageContext>();
        var called = new IntArrayList();
        var fault = new IllegalStateException("first notification");
        for (int index = 0; index < 24; index++) {
            int identity = index;
            contexts.add(new StorageContext(() -> {
                called.add(identity);
                if (identity == 0) {
                    throw fault;
                }
            }));
        }
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(contexts));
        for (var context : contexts) {
            assertTrue(fence.tryAccessWithinBudget(context::changed));
        }
        var ending = fence.beginRelease();
        assertSame(fault, assertThrows(IllegalStateException.class, () -> {
            int calls = 0;
            boolean done;
            do {
                int before = called.size();
                done = ending.advance(new RecipeWorkBudget(1)::take);
                assertTrue(called.size() - before <= 1);
                assertTrue(++calls < 1000);
            } while (!done);
        }));
        assertTrue(ending.done());
        assertEquals(24, called.size());
        for (int index = 0; index < 24; index++) {
            assertEquals(index, called.getInt(index));
        }
        assertTrue(ending.advance(new RecipeWorkBudget(1)::take));
        assertEquals(24, called.size());
    }

    @Test
    void oldCleanupCannotEraseAReplacementFenceOrLaterOrdinaryDirty() {
        var notifications = new AtomicInteger();
        var fault = new IllegalStateException("notification");
        var context = new StorageContext(() -> {
            if (notifications.incrementAndGet() == 1) {
                throw fault;
            }
        });
        var old = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        old.tryAccessWithinBudget(context::changed);
        var ending = old.beginRelease();
        assertFalse(ending.advance(new RecipeWorkBudget(1)::take)); // Dirty captured, RELEASED published; cleanup not attempted.
        context.lock();
        try {
            context.changed();
        } finally {
            context.unlockDeferred();
        }
        var replacement = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        assertSame(fault, assertThrows(IllegalStateException.class, () -> release(ending)));
        assertTrue(ending.done());
        assertFalse(context.tryOrdinaryLock());
        assertTrue(replacement.tryAccessWithinBudget(() -> assertEquals(2, context.revision())));
        replacement.release();
        assertEquals(2, notifications.get());
    }

    @Test
    void ordinaryWaiterStaysBlockedThroughEveryDrainingSliceThenWakes() throws Exception {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
        fence.tryAccessWithinBudget(() -> {
            first.changed();
            second.changed();
        });
        var started = new CountDownLatch(1);
        @SuppressWarnings("resource") var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var reader = pool.submit(() -> {
                started.countDown();
                StorageContext.lockAll(List.of(first, second));
                try {
                    return first.revision() + second.revision();
                } finally {
                    second.unlockDeferred();
                    first.unlockDeferred();
                }
            });
            await(started);
            var ending = fence.beginRelease();
            assertFalse(ending.advance(new RecipeWorkBudget(1)::take));
            assertFalse(first.tryOrdinaryLock());
            assertFalse(second.tryOrdinaryLock());
            assertFalse(reader.isDone());
            release(ending);
            assertEquals(2, reader.get(5, TimeUnit.SECONDS));
        } finally {
            release(fence.beginRelease());
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void gateWaitsForActualSourceExitAndHandsPlainStateToOwnerWithoutASeparatePostWriteSignal() throws Exception {
        var context = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        int[] plainBalance = {0};
        @SuppressWarnings("resource") var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var worker = pool.submit(() -> fence.access(context, () -> {
                entered.countDown();
                await(proceed);
                plainBalance[0] = 37;
                context.changed();
            }));
            await(entered);
            assertFalse(fence.tryAccessWithinBudget(() -> fail("Source still active")));
            assertThrows(IllegalStateException.class, fence::beginRelease);
            proceed.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!fence.tryAccessWithinBudget(() -> {
                assertEquals(37, plainBalance[0]);
                assertEquals(1, context.revision());
            })) {
                assertTrue(System.nanoTime() < until);
                Thread.yield();
            }
            // No join/Future.get/read of a post-write latch preceded the gate-acquired assertion.
            worker.get(5, TimeUnit.SECONDS);
            fence.release();
        } finally {
            proceed.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void distinctSourcesOverlapAndOwnerScopeExcludesNewSourcesUntilItActuallyExits() throws Exception {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
        var bothEntered = new CountDownLatch(2);
        var proceed = new CountDownLatch(1);
        @SuppressWarnings("resource") var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var a = pool.submit(() -> fence.access(first, () -> {
                bothEntered.countDown();
                await(proceed);
            }));
            var b = pool.submit(() -> fence.access(second, () -> {
                bothEntered.countDown();
                await(proceed);
            }));
            await(bothEntered);
            assertFalse(fence.tryAccessWithinBudget(() -> fail("Sources still active")));
            proceed.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            var started = new CountDownLatch(1);
            var sourceEntered = new CountDownLatch(1);
            assertTrue(fence.tryAccessWithinBudget(() -> {
                pool.submit(() -> {
                    started.countDown();
                    fence.access(first, sourceEntered::countDown);
                });
                await(started);
                assertEquals(1, sourceEntered.getCount());
            }));
            await(sourceEntered);
            // Awaiting its callback does not prove exit: acquire through the fence gate again.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!fence.tryAccessWithinBudget(() -> {
            })) {
                assertTrue(System.nanoTime() < until);
                Thread.yield();
            }
            fence.release();
        } finally {
            proceed.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void legacyBaseHoldsAndNewOwnerExplicitHoldsRemainSymmetricAfterCallbackFailure() {
        var context = new StorageContext(() -> {
        });
        var outside = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        fence.access(() -> {
            assertThrows(IllegalStateException.class, context::unlockDeferred);
            context.lock();
            context.unlockDeferred();
            context.requireLock();
        });
        var fault = new IllegalStateException("owner callback");
        assertSame(fault, assertThrows(IllegalStateException.class, () -> fence.tryAccessWithinBudget(() -> {
            context.requireLock();
            context.lock();
            context.unlockDeferred();
            assertThrows(IllegalStateException.class, outside::requireLock);
            assertThrows(IllegalStateException.class, fence::beginRelease);
            assertThrows(IllegalStateException.class, () -> StorageFence.beginAcquire(List.of()));
            throw fault;
        })));
        assertThrows(IllegalStateException.class, context::requireLock);
        assertTrue(fence.tryAccessWithinBudget(context::changed));
        fence.release();
        context.lock();
        try {
            assertEquals(1, context.revision());
        } finally {
            context.unlock();
        }
    }
}
