package com.circulation.prototype_eng.api.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageDeferredBudgetBoundaryTest {
    private static final KeyType TYPE = new KeyType("deferred_revision_boundary");
    private static final Key KEY = new Key(TYPE, "material");

    private static RecipeWorkBudget unit() {
        return new RecipeWorkBudget(1);
    }

    private static SlottedResourceBuffer buffer(int slots, long capacity, long initialRevision) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, capacity);
        var storage = new SlottedResourceBuffer(slots, capacities, () -> {});
        var context = storage.context();
        context.lock();
        try {
            context.initializeRevision(initialRevision);
        } finally {
            context.unlock();
        }
        return storage;
    }

    private static void drive(BooleanSupplier work) {
        for (int step = 0; step < 10_000; step++) {
            if (work.getAsBoolean()) {
                return;
            }
            StorageContext.requireNoLocks();
        }
        throw new AssertionError("Original budget cursor did not finish");
    }

    private static boolean inFence(StorageFence fence, StorageContext context, BooleanSupplier work) {
        var done = new boolean[1];
        fence.access(context, () -> done[0] = work.getAsBoolean());
        return done[0];
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    private static ResourceStorage.ReservationCapture insertCapture(StorageFence fence,
                                                                   SlottedResourceBuffer storage, long amount) {
        var capture = new ResourceStorage.ReservationCapture[1];
        fence.access(storage.context(), () -> capture[0] = storage.beginReserveInsert(storage.snapshotFor(KEY), KEY, amount));
        drive(() -> inFence(fence, storage.context(), () -> capture[0].advance(unit())));
        return capture[0];
    }

    @Test
    void deferredReserveAtRevisionLimitRejectsBeforeEffectsAndKeepsAllThreeFinalTokenTransitions() {
        var rejectedStorage = buffer(1, 100, Long.MAX_VALUE - 4);
        assertEquals(20, rejectedStorage.insert(KEY, 20, Actionable.MODULATE));
        var rejected = rejectedStorage.beginReserveExtract(rejectedStorage.snapshotFor(KEY), KEY, 10);
        assertThrows(IllegalStateException.class, () -> drive(() -> rejected.advance(unit())));
        assertFalse(rejected.hasObligations());
        assertEquals(20, rejectedStorage.availableAmount(KEY));
        assertEquals(20, rejectedStorage.getAmount(0));
        // Both remaining ordinary changes still work: failed preflight did not spend token credits.
        assertEquals(1, rejectedStorage.insert(KEY, 1, Actionable.MODULATE));
        assertEquals(1, rejectedStorage.extract(KEY, 1, Actionable.MODULATE));
        assertThrows(IllegalStateException.class, () -> rejectedStorage.insert(KEY, 1, Actionable.MODULATE));
        rejectedStorage.context().invalidate();
        assertEquals(20, rejectedStorage.getAmount(0));

        var storage = buffer(1, 100, Long.MAX_VALUE - 6);
        assertEquals(20, storage.insert(KEY, 20, Actionable.MODULATE));
        var capture = storage.beginReserveExtract(storage.snapshotFor(KEY), KEY, 10);
        drive(() -> capture.advance(unit()));
        assertEquals(10, capture.amount());
        var token = accepted(capture.result());
        assertEquals(10, storage.availableAmount(KEY));
        assertThrows(IllegalStateException.class, () -> storage.insert(KEY, 1, Actionable.MODULATE));
        var pending = ResourceBatch.beginAcquireSequential(List.of(storage.context()), List.of(token), List.of());
        drive(() -> pending.advance(unit()));
        var batch = Objects.requireNonNull(pending.result());
        var source = batch.sources().getFirst();
        drive(() -> {
            assertTrue(source.applyWithinBudget(unit()));
            return source.state() == ResourceBatch.SourceState.APPLIED;
        });
        assertTrue(batch.accessWithinBudget(() -> {
            assertEquals(10, storage.amount(KEY));
            assertThrows(IllegalStateException.class, () -> storage.insert(KEY, 1, Actionable.MODULATE));
            // Source apply+expose have spent two final credits. Invalidation cannot steal refund.
            storage.context().invalidate();
        }));
        drive(() -> batch.rollbackWithinBudget(unit()));
        drive(() -> source.resolveWithinBudget(unit()));
        assertTrue(batch.accessWithinBudget(() -> {
            assertEquals(ResourceReservation.Resolution.ROLLED_BACK, token.resolution());
            assertEquals(20, storage.getAmount(0));
            assertEquals(Long.MAX_VALUE, storage.context().revision());
        }));
        drive(() -> batch.finishWithinBudget(unit()));
        assertTrue(batch.finished());
        assertEquals(0, storage.extract(KEY, 1, Actionable.MODULATE));
    }

    @Test
    void eachRealNativeJournalClearsOnlyItsOwnContributionAndKeepsAnotherTokensReservedCleanup() {
        var storage = buffer(4, 10, Long.MAX_VALUE - 12);
        var firstCapture = storage.beginReserveInsert(storage.snapshotFor(KEY), KEY, 20);
        drive(() -> firstCapture.advance(unit()));
        var first = accepted(firstCapture.result());
        var secondCapture = storage.beginReserveInsert(storage.snapshotFor(KEY), KEY, 20);
        drive(() -> secondCapture.advance(unit()));
        var second = accepted(secondCapture.result());
        var context = storage.context();
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));

        // Both already accepted tokens start genuine partial slot mutations in the same descriptor.
        drive(() -> {
            assertFalse(inFence(fence, context, () -> first.applyWithinBudget(unit())));
            var changed = new boolean[1];
            fence.access(context, () -> changed[0] = storage.getAmount(0) == 10);
            return changed[0];
        });
        drive(() -> {
            assertFalse(inFence(fence, context, () -> second.applyWithinBudget(unit())));
            var changed = new boolean[1];
            fence.access(context, () -> changed[0] = storage.getAmount(2) == 10);
            return changed[0];
        });
        drive(() -> inFence(fence, context, () -> first.applyWithinBudget(unit())));
        var stillPending = insertCapture(fence, storage, 1);
        assertEquals(ReservationResult.Status.STALE_SNAPSHOT, stillPending.result().status());
        assertFalse(stillPending.hasObligations());

        // A complete rollback creates and finishes another journal; second's original one survives.
        drive(() -> inFence(fence, context, () -> first.rollbackWithinBudget(unit())));
        var afterRefund = insertCapture(fence, storage, 1);
        assertEquals(ReservationResult.Status.STALE_SNAPSHOT, afterRefund.result().status());
        fence.access(context, () -> {
            assertEquals(0, storage.getAmount(0));
            assertEquals(10, storage.getAmount(2));
        });

        drive(() -> inFence(fence, context, () -> second.applyWithinBudget(unit())));
        var nowCurrent = insertCapture(fence, storage, 10);
        assertEquals(10, nowCurrent.amount());
        var third = accepted(nowCurrent.result());
        drive(() -> inFence(fence, context, () -> third.rollbackWithinBudget(unit())));
        fence.access(context, () -> {
            assertEquals(1, storage.insert(KEY, 1, Actionable.MODULATE));
            assertEquals(1, storage.insert(KEY, 1, Actionable.MODULATE));
            assertThrows(IllegalStateException.class, () -> storage.insert(KEY, 1, Actionable.MODULATE));
            context.invalidate();
        });
        // Returned credits from first/third did not consume second's promised terminal transition.
        drive(() -> inFence(fence, context, () -> second.completeWithinBudget(unit())));
        fence.access(context, () -> {
            assertEquals(ResourceReservation.Resolution.ROLLED_BACK, first.resolution());
            assertEquals(ResourceReservation.Resolution.ROLLED_BACK, third.resolution());
            assertEquals(ResourceReservation.Resolution.COMPLETED, second.resolution());
            long saved = 0;
            for (int slot = 0; slot < storage.size(); slot++) {
                saved += storage.getAmount(slot);
            }
            assertEquals(22, saved);
        });
        fence.release();
        assertFalse(storage.isValid());
    }

    @Test
    void defaultLegacyCaptureAfterOneSimulationCanCancelInItsActualBatchSourceWorker() throws Exception {
        var storage = buffer(1, 100, 0);
        assertEquals(20, storage.insert(KEY, 20, Actionable.MODULATE));
        var owner = Thread.currentThread();
        var simulations = new int[1];
        var reserveCalls = new int[1];
        ResourceStorage external = new ResourceStorage() {
            @Override public StorageContext context() { return storage.context(); }
            @Override public StorageSnapshot snapshot() { return storage.snapshot(); }
            @Override public long amount(AEKey key) { return storage.amount(key); }
            @Override public long availableAmount(AEKey key) { return storage.availableAmount(key); }
            @Override public long insert(AEKey key, long amount, Actionable mode) { return storage.insert(key, amount, mode); }
            @Override public long extract(AEKey key, long amount, Actionable mode) {
                assertSame(owner, Thread.currentThread());
                assertSame(Actionable.SIMULATE, mode);
                simulations[0]++;
                return storage.extract(key, amount, mode);
            }
            @Override public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
                reserveCalls[0]++;
                throw new AssertionError("Cancellation must not issue the unentered reserve callback");
            }
            @Override public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
                reserveCalls[0]++;
                throw new AssertionError("Cancellation must not issue a different reserve callback");
            }
        };
        var capture = external.beginReserveExtract(external.snapshot(), KEY, 5);
        var allowance = unit();
        assertFalse(capture.advance(allowance));
        assertEquals(0, allowance.remaining());
        assertEquals(1, simulations[0]);
        assertThrows(IllegalStateException.class, capture::result);
        assertEquals(20, storage.availableAmount(KEY));

        var originalFence = new StorageFence[1];
        var actualWorker = new Thread[1];
        var originalContext = storage.context();
        ResourceReservation pendingReceipt = new ResourceReservation() {
            @Override public StorageContext context() { return originalContext; }
            @Override public boolean canApply() { throw new AssertionError("Unentered reservation cannot be applied"); }
            @Override public void apply() { throw new AssertionError("Cleanup cannot apply a capture"); }
            @Override public void complete() { throw new AssertionError("Cleanup cannot complete a capture"); }
            @Override public void rollback() { throw new AssertionError("The original source must use its budget callback"); }
            @Override public Resolution resolution() { return capture.resolution(); }
            @Override public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
                actualWorker[0] = Thread.currentThread();
                assertNotSame(owner, actualWorker[0]);
                originalContext.requireLock();
                assertSame(originalFence[0], originalContext.requireFenceAccess());
                assertThrows(IllegalStateException.class, originalContext::unlockDeferred);
                assertTrue(capture.cancel(budget));
                assertEquals(Resolution.ROLLED_BACK, capture.resolutionWithinBudget(budget));
                assertEquals(20, storage.amount(KEY));
                return true;
            }
        };
        var pendingBatch = ResourceBatch.beginAcquire(List.of(pendingReceipt), List.of());
        drive(() -> pendingBatch.advance(unit()));
        var batch = Objects.requireNonNull(pendingBatch.result());
        assertTrue(batch.accessWithinBudget(() -> originalFence[0] = originalContext.requireFenceAccess()));
        drive(() -> batch.rollbackWithinBudget(unit()));
        var source = batch.sources().getFirst();
        @SuppressWarnings("resource") var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            worker.submit(() -> {
                drive(() -> source.resolveWithinBudget(unit()));
                StorageContext.requireNoLocks();
                assertThrows(IllegalStateException.class, originalContext::requireLock);
            }).get(5, TimeUnit.SECONDS);
            assertNotSame(owner, Objects.requireNonNull(actualWorker[0]));
            assertTrue(batch.readyToFinish());
            assertEquals(1, simulations[0]);
            assertEquals(0, reserveCalls[0]);
            assertEquals(0, capture.amount());
            assertEquals(ReservationResult.Status.INSUFFICIENT, capture.result().status());
            assertFalse(capture.hasObligations());
            assertTrue(batch.accessWithinBudget(() -> assertEquals(20, storage.availableAmount(KEY))));
            drive(() -> batch.finishWithinBudget(unit()));
            assertTrue(batch.finished());
            assertEquals(20, storage.amount(KEY));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
