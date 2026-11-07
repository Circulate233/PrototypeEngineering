package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlottedResourceBufferTest {
    private static final KeyType FIRST = new KeyType("reserved_first");
    private static final KeyType SECOND = new KeyType("reserved_second");
    private static final Key MATERIAL = new Key(FIRST, "material");
    private static final Key OTHER = new Key(SECOND, "other");

    private static SlottedResourceBuffer buffer(int slots, long capacity) {
        return buffer(slots, capacity, () -> {
        });
    }

    private static SlottedResourceBuffer buffer(int slots, long capacity, Runnable onChanged) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, capacity);
        capacities.put(SECOND, capacity);
        return new SlottedResourceBuffer(slots, capacities, onChanged);
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    @Test
    void outputHoldsCompeteAcrossKeysAndShareTheSameKeysRemainingCapacity() {
        var output = buffer(1, 100);
        var first = accepted(output.reserveInsert(output.snapshot(), MATERIAL, 70));
        assertEquals(0, output.amount(MATERIAL));
        assertEquals(ReservationResult.Status.INSUFFICIENT,
            output.reserveInsert(output.snapshot(), OTHER, 1).status());
        assertEquals(0, output.insert(OTHER, 100, Actionable.MODULATE));
        var second = accepted(output.reserveInsert(output.snapshot(), MATERIAL, 30));
        assertEquals(0, output.insert(MATERIAL, 1, Actionable.SIMULATE));
        assertTrue(ResourceTransactions.commit(List.of(first, second)));
        assertEquals(100, output.amount(MATERIAL));

        var drain = accepted(output.reserveExtract(output.snapshot(), MATERIAL, 100));
        assertTrue(ResourceTransactions.commit(List.of(drain)));
        var replacement = accepted(output.reserveInsert(output.snapshot(), OTHER, 100));
        assertTrue(ResourceTransactions.commit(List.of(replacement)));
        assertEquals(0, output.amount(MATERIAL));
        assertEquals(100, output.amount(OTHER));
    }

    @Test
    void completedInputHoldsReleaseAndTheRemainingBalanceCanBeReservedAgain() {
        var input = buffer(1, 100);
        input.insert(MATERIAL, 100, Actionable.MODULATE);
        var first = accepted(input.reserveExtract(input.snapshot(), MATERIAL, 40));
        assertTrue(ResourceTransactions.commit(List.of(first)));
        assertEquals(60, input.amount(MATERIAL));
        assertEquals(60, input.availableAmount(MATERIAL));
        var second = accepted(input.reserveExtract(input.snapshot(), MATERIAL, 60));
        assertTrue(ResourceTransactions.commit(List.of(second)));
        assertEquals(0, input.amount(MATERIAL));
        assertEquals(100, input.insert(OTHER, 100, Actionable.MODULATE));
    }

    @Test
    void aLaterApplyFaultRollsBackAllParticipantsInReverseOrder() {
        var input = buffer(1, 100);
        var output = buffer(1, 100);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var take = new RecordingReservation(
            accepted(input.reserveExtract(input.snapshot(), MATERIAL, 40)), "input", events);
        var give = new RecordingReservation(
            accepted(output.reserveInsert(output.snapshot(), MATERIAL, 40)), "output", events);
        var failure = new IllegalStateException("later participant failed");
        var broken = new FailingReservation(new StorageContext(() -> {
        }), failure, events);

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> ResourceTransactions.commit(List.of(take, give, broken))));
        assertEquals(List.of("apply:input", "apply:output", "apply:failure",
            "rollback:failure", "rollback:output", "rollback:input"), events);
        assertEquals(80, input.amount(MATERIAL));
        assertEquals(80, input.availableAmount(MATERIAL));
        assertEquals(0, output.amount(MATERIAL));
        var capacity = accepted(output.reserveInsert(output.snapshot(), OTHER, 100));
        ResourceTransactions.rollback(List.of(capacity));
        assertEquals(100, output.insert(OTHER, 100, Actionable.MODULATE));
        assertEquals(80, input.extract(MATERIAL, 100, Actionable.MODULATE));
    }

    @Test
    void invalidatingOneParticipantRejectsTheBatchWithoutWritingOrRevivingOldTokens() {
        var input = buffer(1, 100);
        var output = buffer(1, 100);
        input.insert(MATERIAL, 100, Actionable.MODULATE);
        var take = accepted(input.reserveExtract(input.snapshot(), MATERIAL, 50));
        var give = accepted(output.reserveInsert(output.snapshot(), MATERIAL, 50));
        input.context().invalidate();
        assertFalse(ResourceTransactions.commit(List.of(take, give)));
        assertEquals(100, input.getAmount(0));
        assertEquals(0, output.amount(MATERIAL));
        assertEquals(0, input.extract(MATERIAL, 100, Actionable.MODULATE));
        assertEquals(ReservationResult.Status.INVALID_STORAGE,
            input.reserveExtract(input.snapshot(), MATERIAL, 1).status());
        assertThrows(IllegalStateException.class, () -> ResourceTransactions.commit(List.of(take)));

        var replacement = buffer(1, 100);
        replacement.insert(MATERIAL, 100, Actionable.MODULATE);
        assertThrows(IllegalStateException.class, () -> ResourceTransactions.commit(List.of(take, give)));
        assertEquals(100, replacement.amount(MATERIAL));
        var fresh = accepted(output.reserveInsert(output.snapshot(), OTHER, 100));
        assertTrue(ResourceTransactions.commit(List.of(fresh)));
        assertEquals(100, output.amount(OTHER));
    }

    @Test
    void snapshotsStayImmutableAndAChangedBalanceRejectsStalePlanning() {
        var input = buffer(2, 100);
        input.restore(List.of(new GenericStack(MATERIAL, 30), new GenericStack(OTHER, 60)));
        var snapshot = input.snapshot();
        input.extract(MATERIAL, 10, Actionable.MODULATE);
        assertEquals(30, snapshot.available().getLong(MATERIAL));
        assertEquals(60, snapshot.available().getLong(OTHER));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.available().put(MATERIAL, 1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.available().addTo(MATERIAL, 1));
        assertEquals(ReservationResult.Status.STALE_SNAPSHOT,
            input.reserveExtract(snapshot, MATERIAL, 5).status());
        assertEquals(20, input.availableAmount(MATERIAL));
    }

    @Test
    void mutatingTheCallersListCannotEscapeCompensationAndNotificationFaultsPreserveTheApplyFailure() {
        var notifications = new ObjectArrayList<String>();
        var enabled = new boolean[]{false};
        var notificationFailure = new IllegalStateException("first listener failed");
        var input = buffer(1, 100, () -> {
            if (enabled[0]) {
                notifications.add("input");
                throw notificationFailure;
            }
        });
        var output = buffer(1, 100, () -> {
            if (enabled[0]) {
                notifications.add("output");
            }
        });
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var participants = new ObjectArrayList<ResourceReservation>();
        participants.add(accepted(input.reserveExtract(input.snapshot(), MATERIAL, 40)));
        participants.add(accepted(output.reserveInsert(output.snapshot(), MATERIAL, 40)));
        var applyFailure = new IllegalStateException("external participant failed after clearing the list");
        participants.add(new MutatingFailingReservation(new StorageContext(() -> {
        }), applyFailure, participants));
        enabled[0] = true;

        assertSame(applyFailure, assertThrows(IllegalStateException.class,
            () -> ResourceTransactions.commit(participants)));
        assertTrue(participants.isEmpty());
        assertEquals(List.of("input", "output"), notifications);
        assertArrayEquals(new Throwable[]{notificationFailure}, applyFailure.getSuppressed());
        assertEquals(80, input.amount(MATERIAL));
        assertEquals(80, input.availableAmount(MATERIAL));
        assertEquals(0, output.amount(MATERIAL));
        enabled[0] = false;
        assertEquals(100, output.insert(OTHER, 100, Actionable.MODULATE));
    }

    @Test
    void largeMultiTypeBalancesAndCombinedHoldsNeverOverflowLong() {
        var storage = buffer(2, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE - 10,
            storage.insert(0, MATERIAL, Long.MAX_VALUE - 10, Actionable.MODULATE));
        var headroom = accepted(storage.reserveInsert(storage.snapshot(), MATERIAL, 10));
        assertEquals(ReservationResult.Status.INSUFFICIENT,
            storage.reserveInsert(storage.snapshot(), MATERIAL, 1).status());
        assertEquals(0, storage.insert(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(5_000_000_000L, storage.insert(1, OTHER, 5_000_000_000L, Actionable.MODULATE));
        assertTrue(ResourceTransactions.commit(List.of(headroom)));
        assertEquals(Long.MAX_VALUE, storage.amount(MATERIAL));
        assertEquals(5_000_000_000L, storage.amount(OTHER));
        var almostAll = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, Long.MAX_VALUE - 2));
        assertEquals(ReservationResult.Status.INSUFFICIENT,
            storage.reserveExtract(storage.snapshot(), MATERIAL, 3).status());
        assertEquals(2, storage.extract(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE));
        assertTrue(ResourceTransactions.commit(List.of(almostAll)));
        assertEquals(0, storage.amount(MATERIAL));
        assertEquals(5_000_000_000L, storage.snapshot().available().getLong(OTHER));
    }

    @Test
    void simultaneousInputReservationsCannotAllocateMoreThanTheRealBalance() throws Exception {
        var input = buffer(1, 100);
        input.insert(MATERIAL, 100, Actionable.MODULATE);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        // ExecutorService.close could wait forever when diagnosing a real storage deadlock.
        @SuppressWarnings("resource")
        var executor = Executors.newFixedThreadPool(8, Thread.ofPlatform().daemon().factory());
        try {
            var futures = new ObjectArrayList<Future<ReservationResult>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    var snapshot = input.snapshot();
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return input.reserveExtract(snapshot, MATERIAL, 20);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var reservations = new ObjectArrayList<ResourceReservation>();
            for (var future : futures) {
                var result = future.get(5, TimeUnit.SECONDS);
                if (result.status() == ReservationResult.Status.ACCEPTED) {
                    reservations.add(Objects.requireNonNull(result.reservation()));
                } else {
                    assertTrue(result.status() == ReservationResult.Status.STALE_SNAPSHOT
                        || result.status() == ReservationResult.Status.INSUFFICIENT);
                }
            }
            assertTrue(!reservations.isEmpty() && reservations.size() <= 5);
            long held = 20L * reservations.size();
            assertEquals(100 - held, input.availableAmount(MATERIAL));
            assertEquals(100 - held, input.extract(MATERIAL, 100, Actionable.MODULATE));
            assertTrue(ResourceTransactions.commit(reservations));
            assertEquals(0, input.amount(MATERIAL));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oppositeParticipantOrdersCommitConcurrentlyWithoutDeadlockOrLosingResources() throws Exception {
        var first = buffer(1, 200);
        var second = buffer(1, 200);
        first.insert(MATERIAL, 100, Actionable.MODULATE);
        second.insert(MATERIAL, 100, Actionable.MODULATE);
        // Keep deadlock failures bounded while verifying normal runs leave no worker threads.
        @SuppressWarnings("resource")
        var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            for (int round = 0; round < 32; round++) {
                var firstTake = accepted(first.reserveExtract(first.snapshot(), MATERIAL, 10));
                var secondGive = accepted(second.reserveInsert(second.snapshot(), MATERIAL, 10));
                var secondTake = accepted(second.reserveExtract(second.snapshot(), MATERIAL, 10));
                var firstGive = accepted(first.reserveInsert(first.snapshot(), MATERIAL, 10));
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                var forward = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return ResourceTransactions.commit(List.of(firstTake, secondGive));
                });
                var reverse = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return ResourceTransactions.commit(List.of(secondTake, firstGive));
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(forward.get(5, TimeUnit.SECONDS));
                assertTrue(reverse.get(5, TimeUnit.SECONDS));
                assertEquals(100, first.amount(MATERIAL));
                assertEquals(100, second.amount(MATERIAL));
                assertEquals(200, first.availableAmount(MATERIAL) + second.availableAmount(MATERIAL));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private record RecordingReservation(ResourceReservation delegate, String name, List<String> events)
        implements ResourceReservation {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean canApply() {
            return delegate.canApply();
        }

        @Override
        public void apply() {
            events.add("apply:" + name);
            delegate.apply();
        }

        @Override
        public void complete() {
            delegate.complete();
        }

        @Override
        public void rollback() {
            events.add("rollback:" + name);
            delegate.rollback();
        }
    }

    private record FailingReservation(StorageContext context, RuntimeException failure, List<String> events)
        implements ResourceReservation {
        @Override
        public boolean canApply() {
            context.requireLock();
            return true;
        }

        @Override
        public void apply() {
            context.requireLock();
            events.add("apply:failure");
            throw failure;
        }

        @Override
        public void complete() {
            throw new AssertionError("Failed participant cannot complete");
        }

        @Override
        public void rollback() {
            context.requireLock();
            events.add("rollback:failure");
        }
    }

    private record MutatingFailingReservation(StorageContext context, RuntimeException failure,
                                               List<ResourceReservation> participants) implements ResourceReservation {
        @Override
        public boolean canApply() {
            context.requireLock();
            return true;
        }

        @Override
        public void apply() {
            context.requireLock();
            participants.clear();
            throw failure;
        }

        @Override
        public void complete() {
            throw new AssertionError("Failed participant cannot complete");
        }

        @Override
        public void rollback() {
            context.requireLock();
        }
    }
}
