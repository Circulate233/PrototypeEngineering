package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SlottedSequentialReservationTest {
    private static final KeyType FIRST = new KeyType("sequence_first");
    private static final KeyType SECOND = new KeyType("sequence_second");
    private static final Key MATERIAL = new Key(FIRST, "material");
    private static final Key OTHER = new Key(SECOND, "other");

    @Test void anEmptiedSlotCanChangeResourceTypeAndReverseRollbackRestoresTheOriginalAllocation() {
        var storage = buffer(2, 100);
        storage.insert(MATERIAL, 200, Actionable.MODULATE);
        var start = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 150));
        var fence = fence(storage);
        try {
            fence.access(() -> {
                start.apply();
                assertEquals(ReservationResult.Status.INSUFFICIENT, storage.reserveInsert(storage.snapshot(), OTHER, 60).status());
                start.exposeApplied();
                var end = accepted(storage.reserveInsert(storage.snapshot(), OTHER, 60));
                end.apply(); end.exposeApplied();
                assertEquals(new GenericStack(OTHER, 60), storage.getStack(0));
                assertEquals(new GenericStack(MATERIAL, 50), storage.getStack(1));
                assertThrows(IllegalStateException.class, start::rollback);
                assertEquals(new GenericStack(OTHER, 60), storage.getStack(0));
                assertEquals(new GenericStack(MATERIAL, 50), storage.getStack(1));
                assertEquals(ResourceReservation.Resolution.APPLIED, start.resolution());
                end.rollback(); start.rollback();
                assertEquals(new GenericStack(MATERIAL, 100), storage.getStack(0));
                assertEquals(new GenericStack(MATERIAL, 100), storage.getStack(1));
                assertEquals(200, storage.availableAmount(MATERIAL));
                assertEquals(0, storage.amount(OTHER));
            });
        } finally { fence.release(); }
    }

    @Test void outputsCanFeedLaterInputsWithoutGivingUpTheirWholeSequenceUndo() {
        for (boolean complete : new boolean[]{false, true}) {
            var storage = buffer(2, 100);
            var output = accepted(storage.reserveInsert(storage.snapshot(), MATERIAL, 150));
            var fence = fence(storage);
            try {
                fence.access(() -> {
                    output.apply();
                    assertEquals(0, storage.availableAmount(MATERIAL));
                    output.exposeApplied();
                    var input = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 120));
                    input.apply(); input.exposeApplied();
                    assertEquals(30, storage.availableAmount(MATERIAL));
                    if (complete) { output.complete(); input.complete(); }
                    else {
                        assertThrows(IllegalStateException.class, output::rollback);
                        input.rollback(); output.rollback();
                    }
                    assertEquals(complete ? 30 : 0, storage.amount(MATERIAL));
                    assertEquals(complete ? ResourceReservation.Resolution.COMPLETED : ResourceReservation.Resolution.ROLLED_BACK, output.resolution());
                    assertEquals(output.resolution(), input.resolution());
                });
            } finally { fence.release(); }
        }
    }

    @Test void aLaterSlotConflictIsDetectedBeforeRestoringAnyEarlierSlot() {
        var storage = buffer(2, 100);
        storage.insert(MATERIAL, 200, Actionable.MODULATE);
        var input = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 200));
        var fence = fence(storage);
        try {
            fence.access(() -> {
                input.apply(); input.exposeApplied();
                var output = accepted(storage.reserveInsert(storage.snapshot(), OTHER, 200));
                output.apply(); output.exposeApplied();
                var consume = accepted(storage.reserveExtract(storage.snapshot(), OTHER, 100));
                consume.apply(); consume.exposeApplied();
                assertNull(storage.getStack(0));
                assertEquals(new GenericStack(OTHER, 100), storage.getStack(1));
                assertThrows(IllegalStateException.class, input::rollback);
                assertNull(storage.getStack(0), "No partial restoration before a later allocation fails");
                assertEquals(0, storage.amount(MATERIAL));
                consume.rollback(); output.rollback(); input.rollback();
                assertEquals(200, storage.availableAmount(MATERIAL));
                assertEquals(0, storage.amount(OTHER));
            });
        } finally { fence.release(); }
    }

    @Test void exposingOneTokenPreservesOtherPendingAndAppliedHoldsInTheSameSlots() {
        var storage = buffer(2, 100);
        storage.insert(MATERIAL, 80, Actionable.MODULATE);
        var olderInput = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 10));
        var olderOutput = accepted(storage.reserveInsert(storage.snapshot(), MATERIAL, 20));
        var input = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 70));
        var fence = fence(storage);
        try {
            fence.access(() -> {
                olderInput.apply();
                input.apply(); input.exposeApplied();
                assertEquals(0, storage.getAmount(0));
                assertEquals(0, storage.insert(0, OTHER, 1, Actionable.SIMULATE));
                assertEquals(70, storage.insert(0, MATERIAL, 100, Actionable.SIMULATE));
                var later = accepted(storage.reserveInsert(storage.snapshot(), MATERIAL, 70));
                later.apply(); later.exposeApplied();
                assertThrows(IllegalStateException.class, input::rollback);
                later.rollback(); input.rollback();
                assertEquals(70, storage.availableAmount(MATERIAL));
                assertEquals(0, storage.insert(0, MATERIAL, 1, Actionable.SIMULATE));
                olderInput.rollback(); olderOutput.rollback();
                assertEquals(80, storage.availableAmount(MATERIAL));
                assertEquals(20, storage.insert(0, MATERIAL, 100, Actionable.SIMULATE));
            });
        } finally { fence.release(); }
    }

    @Test void longTotalOverflowRejectsRollbackBeforeChangingAnyBalance() {
        var storage = buffer(2, Long.MAX_VALUE);
        storage.insert(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE);
        var input = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, Long.MAX_VALUE));
        var fence = fence(storage);
        try {
            fence.access(() -> {
                input.apply(); input.exposeApplied();
                var output = accepted(storage.reserveInsert(storage.snapshot(), MATERIAL, Long.MAX_VALUE));
                output.apply(); output.exposeApplied();
                assertThrows(ArithmeticException.class, input::rollback);
                assertEquals(Long.MAX_VALUE, storage.amount(MATERIAL));
                assertEquals(0, storage.insert(MATERIAL, 1, Actionable.SIMULATE));
                output.rollback(); input.rollback();
                assertEquals(Long.MAX_VALUE, storage.availableAmount(MATERIAL));
                assertEquals(Long.MAX_VALUE, storage.getAmount(0));
                assertNull(storage.getStack(1));
            });
        } finally { fence.release(); }
    }

    @Test void exposureRequiresAppliedStateAndTheExactRetainedFenceUntilTerminal() {
        var storage = buffer(1, 100);
        storage.insert(MATERIAL, 80, Actionable.MODULATE);
        var token = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 20));
        storage.context().lock();
        try {
            assertThrows(IllegalStateException.class, token::exposeApplied);
            token.apply();
            assertThrows(IllegalStateException.class, token::exposeApplied);
            assertEquals(ResourceReservation.Resolution.APPLIED, token.resolution());
        } finally { storage.context().unlock(); }
        var fence = fence(storage);
        var foreign = fence(buffer(1, 100));
        try {
            fence.access(() -> {
                assertTrue(token.supportsSequentialUse());
                token.exposeApplied();
                assertThrows(IllegalStateException.class, token::exposeApplied);
                assertThrows(IllegalStateException.class, token::apply);
                assertThrows(IllegalStateException.class, token::canApply);
                assertThrows(IllegalStateException.class, () -> storage.restore(List.of(new GenericStack(MATERIAL, 60))));
            });
            assertThrows(IllegalStateException.class, fence::release);
            assertThrows(IllegalStateException.class, storage.context()::lock);
            foreign.access(() -> {
                assertThrows(IllegalStateException.class, token::resolution);
                assertThrows(IllegalStateException.class, token::complete);
                assertThrows(IllegalStateException.class, token::rollback);
            });
            fence.access(() -> {
                token.complete();
                assertThrows(IllegalStateException.class, token::exposeApplied);
                assertThrows(IllegalStateException.class, token::complete);
                assertThrows(IllegalStateException.class, token::rollback);
                assertEquals(ResourceReservation.Resolution.COMPLETED, token.resolution());
                storage.restore(List.of(new GenericStack(MATERIAL, 60)));
            });
        } finally { foreign.release(); fence.release(); }
        assertEquals(60, storage.availableAmount(MATERIAL));
    }

    @Test void invalidationStillAllowsExactCompensationAndCompletion() {
        for (boolean complete : new boolean[]{false, true}) {
            var storage = buffer(1, 100);
            storage.insert(MATERIAL, 80, Actionable.MODULATE);
            var token = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 20));
            var fence = fence(storage);
            try {
                fence.access(() -> {
                    token.apply(); token.exposeApplied();
                    storage.context().invalidate();
                    assertEquals(ReservationResult.Status.INVALID_STORAGE, storage.reserveInsert(storage.snapshot(), OTHER, 1).status());
                    if (complete) { token.complete(); }
                    else { token.rollback(); }
                });
            } finally { fence.release(); }
            assertEquals(new GenericStack(MATERIAL, complete ? 60 : 80), storage.getStack(0));
        }
    }

    @Test void sourceLanesCanReuseExposedSlotsWhileExternalReadersWaitForWholeSequenceSettlement() throws Exception {
        int[] notifications = {0};
        var storage = buffer(1, 100, () -> notifications[0]++);
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var input = accepted(storage.reserveExtract(storage.snapshot(), MATERIAL, 100));
        int before = notifications[0];
        var fence = fence(storage);
        var entered = new CountDownLatch(1);
        var reader = new FutureTask<>(() -> { entered.countDown(); return storage.getStack(0); });
        var thread = Thread.ofPlatform().daemon().unstarted(reader);
        try (var lane = Executors.newSingleThreadExecutor()) {
            try {
                lane.submit(() -> fence.access(storage.context(), () -> { input.apply(); input.exposeApplied(); })).get(5, TimeUnit.SECONDS);
                thread.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(reader.isDone());
                var output = fence.access(() -> accepted(storage.reserveInsert(storage.snapshot(), OTHER, 75)));
                lane.submit(() -> fence.access(storage.context(), () -> { output.apply(); output.exposeApplied(); })).get(5, TimeUnit.SECONDS);
                assertEquals(before, notifications[0]);
                assertThrows(IllegalStateException.class, fence::release);
                lane.submit(() -> fence.access(storage.context(), () -> { output.rollback(); input.rollback(); })).get(5, TimeUnit.SECONDS);
                assertFalse(reader.isDone());
            } finally { fence.release(); }
        }
        assertEquals(new GenericStack(MATERIAL, 100), reader.get(5, TimeUnit.SECONDS));
        thread.join(5000);
        assertFalse(thread.isAlive());
        assertEquals(before + 1, notifications[0]);
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }
    private static StorageFence fence(SlottedResourceBuffer storage) { return Objects.requireNonNull(StorageFence.tryAcquire(List.of(storage.context()))); }
    private static SlottedResourceBuffer buffer(int slots, long capacity) { return buffer(slots, capacity, () -> {}); }
    private static SlottedResourceBuffer buffer(int slots, long capacity, Runnable notification) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, capacity); capacities.put(SECOND, capacity);
        return new SlottedResourceBuffer(slots, capacities, notification);
    }
}
