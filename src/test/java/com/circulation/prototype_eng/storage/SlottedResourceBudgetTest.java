package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SlottedResourceBudgetTest {
    private static final KeyType TYPE = new KeyType("budget_slots");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key OTHER = new Key(TYPE, "other");

    private static RecipeWorkBudget unit() {
        return new RecipeWorkBudget(1);
    }

    private static SlottedResourceBuffer buffer(int slots, long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, capacity);
        return new SlottedResourceBuffer(slots, capacities, () -> {
        });
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    private static void drive(BooleanSupplier work) {
        for (int count = 0; count < 10000; count++) {
            if (work.getAsBoolean()) {
                return;
            }
        }
        fail("Native cursor did not reach its terminal boundary");
    }

    @SuppressWarnings("UnusedReturnValue") // Callers inspect the exact storage state after the scoped work.
    private static boolean locked(StorageContext context, BooleanSupplier work) {
        context.lock();
        try {
            return work.getAsBoolean();
        } finally {
            context.unlock();
        }
    }

    private static int heldSlots(SlottedResourceBuffer buffer) {
        int count = 0;
        for (int slot = 0; slot < buffer.size(); slot++) {
            if (buffer.insert(slot, MATERIAL, 1, Actionable.SIMULATE) == 0) {
                count++;
            }
        }
        return count;
    }

    private static ResourceReservation.Resolution captureResolution(ResourceStorage.ReservationCapture capture, StorageContext context) {
        var resolution = new ResourceReservation.Resolution[1];
        locked(context, () -> {
            resolution[0] = capture.resolutionWithinBudget(unit());
            return true;
        });
        return resolution[0];
    }

    private static void rollbackToken(ResourceReservation token) {
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(token.context())));
        drive(() -> inSource(fence, token.context(), () -> token.rollbackWithinBudget(unit())));
        fence.release();
    }

    private static void cancelCapture(ResourceStorage.ReservationCapture capture, StorageContext context) {
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        drive(() -> inSource(fence, context, () -> capture.cancel(unit())));
        fence.release();
    }

    private static long amountInSource(StorageFence fence, SlottedResourceBuffer buffer, boolean available) {
        var amount = new long[1];
        fence.access(buffer.context(), () -> amount[0] = available ? buffer.availableAmount(MATERIAL) : buffer.amount(MATERIAL));
        return amount[0];
    }

    private static long balanceInOwner(ResourceBatch batch, SlottedResourceBuffer buffer) {
        var amount = new long[1];
        assertTrue(batch.accessWithinBudget(() -> amount[0] = buffer.amount(MATERIAL)));
        return amount[0];
    }

    private static ResourceReservation.Resolution lockedResolution(ResourceReservation token) {
        var resolution = new ResourceReservation.Resolution[1];
        locked(token.context(), () -> {
            resolution[0] = token.resolution();
            return true;
        });
        return resolution[0];
    }

    private static boolean inSource(StorageFence fence, StorageContext context, BooleanSupplier work) {
        var done = new boolean[1];
        fence.access(context, () -> done[0] = work.getAsBoolean());
        return done[0];
    }

    @Test
    void upToReserveVisitsAndInstallsOnlyOneActualSlotPerSliceWithoutLeavingLocks() {
        var buffer = buffer(128, 1);
        var snapshot = buffer.snapshot();
        var capture = buffer.beginReserveInsert(snapshot, MATERIAL, 256);
        boolean done;
        do {
            int before = heldSlots(buffer);
            done = capture.advance(unit());
            assertTrue(heldSlots(buffer) - before <= 1);
            assertThrows(IllegalStateException.class, buffer.context()::requireLock);
            if (capture.hasObligations() && !done) {
                assertFalse(buffer.context().current(buffer.snapshot().revision()));
            }
        } while (!done);
        assertEquals(128, capture.amount());
        assertEquals(128, heldSlots(buffer));
        assertFalse(buffer.context().current(snapshot.revision()));
        assertTrue(buffer.context().current(buffer.snapshot().revision()));
        var token = accepted(capture.result());
        assertFalse(capture.hasObligations());
        rollbackToken(token);
        assertEquals(0, heldSlots(buffer));
    }

    @Test
    void partialInstallRejectsChangedRevisionAndRemovesOnlyItsOwnLiveHoldPrefix() {
        var buffer = buffer(8, 10);
        var capture = buffer.beginReserveInsert(buffer.snapshot(), MATERIAL, 20);
        drive(() -> {
            assertFalse(capture.advance(unit()));
            return capture.hasObligations();
        });
        assertEquals(0, buffer.insert(0, MATERIAL, 1, Actionable.SIMULATE));
        long partialRevision = buffer.snapshot().revision();
        assertFalse(buffer.context().current(partialRevision));
        assertEquals(5, buffer.insert(7, OTHER, 5, Actionable.MODULATE));
        drive(() -> capture.advance(unit()));
        assertEquals(ReservationResult.Status.STALE_SNAPSHOT, capture.result().status());
        assertEquals(0, capture.amount());
        assertTrue(capture.hasObligations());
        assertEquals(ResourceReservation.Resolution.REVERSIBLE, captureResolution(capture, buffer.context()));
        assertThrows(IllegalStateException.class, () -> locked(buffer.context(), () -> capture.cancel(unit())));
        cancelCapture(capture, buffer.context());
        assertFalse(capture.hasObligations());
        assertEquals(5, buffer.amount(OTHER));
        assertEquals(10, buffer.insert(0, OTHER, 10, Actionable.MODULATE));
        assertFalse(buffer.context().current(partialRevision));
        assertTrue(buffer.context().current(buffer.snapshot().revision()));
    }

    @Test
    void originalBatchCanClaimAndSourceCancelAnUnpublishedPartialCapture() {
        var buffer = buffer(64, 1);
        ResourceStorage.ReservationCapture capture = buffer.beginReserveInsert(buffer.snapshot(), MATERIAL, 64);
        drive(() -> {
            assertFalse(capture.advance(unit()));
            return capture.hasObligations();
        });
        assertEquals(ResourceReservation.Resolution.REVERSIBLE, captureResolution(capture, buffer.context()));
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(buffer.context())));
        assertThrows(IllegalStateException.class, () -> capture.advance(unit()));
        drive(() -> {
            var done = new boolean[1];
            fence.access(buffer.context(), () -> done[0] = capture.cancel(unit()));
            return done[0];
        });
        assertFalse(capture.hasObligations());
        assertEquals(0, capture.amount());
        fence.access(buffer.context(), () -> assertEquals(ResourceReservation.Resolution.ROLLED_BACK, capture.resolutionWithinBudget(unit())));
        fence.release();
        assertEquals(64, buffer.insert(MATERIAL, 64, Actionable.MODULATE));
    }

    @Test
    void partialApplyRollbackPublishesOnlyCoherentRevisionsAndPreservesOtherOwnerWrites() {
        var buffer = buffer(16, 10);
        buffer.insert(MATERIAL, 120, Actionable.MODULATE);
        var token = accepted(buffer.reserveExtract(buffer.snapshot(), MATERIAL, 80));
        assertThrows(IllegalStateException.class, () -> locked(buffer.context(), () -> token.applyWithinBudget(unit())));
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(buffer.context())));
        drive(() -> {
            assertFalse(inSource(fence, buffer.context(), () -> token.applyWithinBudget(unit())));
            return amountInSource(fence, buffer, false) < 120;
        });
        var partialRevision = new long[1];
        fence.access(buffer.context(), () -> {
            partialRevision[0] = buffer.snapshot().revision();
            assertEquals(ResourceReservation.Resolution.REVERSIBLE, token.resolution());
            assertEquals(5, buffer.insert(15, OTHER, 5, Actionable.MODULATE));
        });
        assertFalse(buffer.context().current(partialRevision[0]));
        assertThrows(IllegalStateException.class, fence::beginRelease);
        drive(() -> inSource(fence, buffer.context(), () -> token.rollbackWithinBudget(unit())));
        fence.release();
        assertEquals(120, buffer.amount(MATERIAL));
        assertEquals(120, buffer.availableAmount(MATERIAL));
        assertEquals(5, buffer.amount(OTHER));
        assertEquals(ResourceReservation.Resolution.ROLLED_BACK, lockedResolution(token));
        assertFalse(buffer.context().current(partialRevision[0]));
        assertTrue(buffer.context().current(buffer.snapshot().revision()));
    }

    @Test
    void partialExposureRetainsOriginalFenceAndRollbackDistinguishesReleasedPrefix() {
        var buffer = buffer(10, 10);
        var token = accepted(buffer.reserveInsert(buffer.snapshot(), MATERIAL, 80));
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(buffer.context())));
        drive(() -> inSource(fence, buffer.context(), () -> token.applyWithinBudget(unit())));
        drive(() -> {
            assertFalse(inSource(fence, buffer.context(), () -> token.exposeAppliedWithinBudget(unit())));
            var exposed = new long[1];
            fence.access(buffer.context(), () -> exposed[0] = buffer.availableAmount(MATERIAL));
            return exposed[0] > 0;
        });
        assertThrows(IllegalStateException.class, fence::beginRelease);
        fence.access(buffer.context(), () -> {
            assertEquals(ResourceReservation.Resolution.REVERSIBLE, token.resolution());
            assertEquals(3, buffer.insert(9, OTHER, 3, Actionable.MODULATE));
        });
        drive(() -> inSource(fence, buffer.context(), () -> token.rollbackWithinBudget(unit())));
        fence.release();
        assertEquals(0, buffer.amount(MATERIAL));
        assertEquals(3, buffer.amount(OTHER));
        assertEquals(10, buffer.insert(0, OTHER, 10, Actionable.MODULATE));
    }

    @Test
    void partialCompleteKeepsItsDecidedDirectionUntilEveryOwnedHoldIsReleased() {
        var buffer = buffer(8, 10);
        var token = accepted(buffer.reserveInsert(buffer.snapshot(), MATERIAL, 80));
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(buffer.context())));
        drive(() -> inSource(fence, buffer.context(), () -> token.applyWithinBudget(unit())));
        drive(() -> {
            assertFalse(inSource(fence, buffer.context(), () -> token.completeWithinBudget(unit())));
            return amountInSource(fence, buffer, true) > 0;
        });
        fence.access(buffer.context(), () -> assertEquals(ResourceReservation.Resolution.REVERSIBLE, token.resolution()));
        assertThrows(IllegalStateException.class, () -> inSource(fence, buffer.context(), () -> token.rollbackWithinBudget(unit())));
        assertThrows(IllegalStateException.class, fence::beginRelease);
        drive(() -> inSource(fence, buffer.context(), () -> token.completeWithinBudget(unit())));
        fence.release();
        assertEquals(ResourceReservation.Resolution.COMPLETED, lockedResolution(token));
        assertEquals(80, buffer.amount(MATERIAL));
        assertEquals(80, buffer.extract(MATERIAL, 80, Actionable.MODULATE));
    }

    @Test
    void batchSourceUsesNativeSlotBudgetInsteadOfTreatingTheWholeTokenAsOneUnit() {
        var buffer = buffer(128, 1);
        buffer.insert(MATERIAL, 128, Actionable.MODULATE);
        var token = accepted(buffer.reserveExtract(buffer.snapshot(), MATERIAL, 128));
        var pending = ResourceBatch.beginAcquireSequential(List.of(buffer.context()), List.of(token), List.of());
        drive(() -> pending.advance(unit()));
        var batch = Objects.requireNonNull(pending.result());
        var source = batch.sources().getFirst();
        while (source.state() != ResourceBatch.SourceState.APPLIED) {
            long before = balanceInOwner(batch, buffer);
            assertTrue(source.applyWithinBudget(unit()));
            assertTrue(before - balanceInOwner(batch, buffer) <= 1);
            assertThrows(IllegalStateException.class, buffer.context()::requireLock);
        }
        drive(() -> batch.stageWithinBudget(() -> true, unit()));
        drive(() -> batch.rollbackWithinBudget(unit()));
        drive(() -> source.resolveWithinBudget(unit()));
        drive(() -> batch.finishWithinBudget(unit()));
        assertTrue(batch.finished());
        assertEquals(128, buffer.amount(MATERIAL));
    }

    @Test
    void upToInputCapturePreservesCompositionAcrossPartiallyAvailableSources() {
        var first = buffer(4, 1);
        var second = buffer(6, 1);
        first.insert(MATERIAL, 4, Actionable.MODULATE);
        second.insert(MATERIAL, 6, Actionable.MODULATE);
        var firstCapture = first.beginReserveExtract(first.snapshot(), MATERIAL, 10);
        drive(() -> firstCapture.advance(unit()));
        assertEquals(4, firstCapture.amount());
        var secondCapture = second.beginReserveExtract(second.snapshot(), MATERIAL, 10 - firstCapture.amount());
        drive(() -> secondCapture.advance(unit()));
        assertEquals(6, secondCapture.amount());
        assertEquals(10, firstCapture.amount() + secondCapture.amount());
        var firstToken = accepted(firstCapture.result());
        var secondToken = accepted(secondCapture.result());
        rollbackToken(firstToken);
        rollbackToken(secondToken);
        assertEquals(4, first.availableAmount(MATERIAL));
        assertEquals(6, second.availableAmount(MATERIAL));
    }

    @Test
    void quantityCaptureDoesNotResumeItsLiveIteratorAcrossAnUnpublishedNativeJournal() {
        var buffer = buffer(3, 10);
        buffer.insert(MATERIAL, 10, Actionable.MODULATE);
        buffer.insert(OTHER, 10, Actionable.MODULATE);
        var quantity = buffer.beginQuantityCapture();
        assertFalse(quantity.advance(unit()));
        var reserve = buffer.beginReserveExtract(buffer.snapshot(), MATERIAL, 5);
        drive(() -> {
            assertFalse(reserve.advance(unit()));
            return reserve.hasObligations();
        });
        var allowance = unit();
        assertFalse(quantity.advance(allowance));
        assertEquals(1, allowance.remaining());
        cancelCapture(reserve, buffer.context());
        drive(() -> quantity.advance(unit()));
        assertTrue(quantity.current());
        assertEquals(10, quantity.available().getLong(MATERIAL));
        assertEquals(10, quantity.available().getLong(OTHER));
    }

    @Test
    void unenumeratedLegacyCaptureEffectRemainsUnknownAndCannotReplayItsReserveCallback() {
        var buffer = buffer(1, 10);
        buffer.insert(MATERIAL, 10, Actionable.MODULATE);
        var calls = new int[1];
        var actual = new ResourceReservation[1];
        ResourceStorage external = new ResourceStorage() {
            @Override
            public StorageContext context() {
                return buffer.context();
            }

            @Override
            public StorageSnapshot snapshot() {
                return buffer.snapshot();
            }

            @Override
            public long amount(AEKey key) {
                return buffer.amount(key);
            }

            @Override
            public long availableAmount(AEKey key) {
                return buffer.availableAmount(key);
            }

            @Override
            public long insert(AEKey key, long amount, Actionable mode) {
                return buffer.insert(key, amount, mode);
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode) {
                return buffer.extract(key, amount, mode);
            }

            @Override
            public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
                calls[0]++;
                actual[0] = accepted(buffer.reserveExtract(snapshot, key, amount));
                throw new IllegalStateException("Legacy callback failed after an unenumerated reserve effect");
            }
        };
        var capture = external.beginReserveExtract(external.snapshot(), MATERIAL, 5);
        try {
            assertFalse(capture.advance(unit()));
            assertThrows(IllegalStateException.class, () -> capture.advance(unit()));
            assertTrue(capture.hasObligations());
            assertEquals(ResourceReservation.Resolution.UNKNOWN, captureResolution(capture, buffer.context()));
            assertThrows(IllegalStateException.class, () -> locked(buffer.context(), () -> capture.cancel(unit())));
            assertThrows(IllegalStateException.class, () -> capture.advance(unit()));
            assertEquals(1, calls[0]);
            assertTrue(capture.hasObligations());
        } finally {
            // The fixture alone retained the opaque callback's real receipt; the capture cannot infer it.
            if (actual[0] != null) {
                locked(buffer.context(), () -> {
                    actual[0].rollback();
                    return true;
                });
            }
        }
    }

    @Test
    void noFencePendingInstallDoesNotRehashAnExistingKeyAfterItsHashStartsThrowing() {
        var buffer = buffer(64, 1);
        var oldKey = new ThrowingHashKey(0);
        var tokens = new ArrayList<ResourceReservation>();
        var first = buffer.beginReserveInsert(buffer.snapshotFor(oldKey), oldKey, 1);
        drive(() -> first.advance(unit()));
        tokens.add(accepted(first.result()));
        oldKey.failHash = true;
        try {
            // The original default 32-bucket hold map would rehash after crossing its 24-key fill.
            for (int keyId = 1; keyId < 26; keyId++) {
                var key = new ThrowingHashKey(keyId);
                var capture = buffer.beginReserveInsert(buffer.snapshotFor(key), key, 1);
                drive(() -> capture.advance(unit()));
                tokens.add(accepted(capture.result()));
                assertEquals(1, capture.amount());
            }
        } finally {
            oldKey.failHash = false;
            var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(buffer.context())));
            for (var token : tokens) {
                drive(() -> inSource(fence, buffer.context(), () -> token.rollbackWithinBudget(unit())));
            }
            fence.release();
        }
        assertEquals(64, buffer.insert(MATERIAL, 64, Actionable.MODULATE));
    }

    @Test
    void ambiguousRawLegacyWriteRetiresOnlyItsContextAndCannotBeUsedForNewConsumptionOrRestore() {
        var buffer = buffer(2, 10);
        var key = new ThrowingHashKey(77);
        var token = accepted(buffer.reserveInsert(buffer.snapshotFor(key), key, 10));
        key.failAfterTotalIn = buffer;
        try {
            assertThrows(IllegalStateException.class, () -> locked(buffer.context(), () -> {
                token.apply();
                return true;
            }));
        } finally {
            key.failAfterTotalIn = null;
        }
        assertFalse(buffer.isValid());
        assertEquals(0, buffer.getAmount(0));
        assertEquals(ResourceReservation.Resolution.UNKNOWN, lockedResolution(token));
        assertEquals(0, buffer.amount(key));
        assertEquals(0, buffer.availableAmount(key));
        assertEquals(0, buffer.insert(MATERIAL, 1, Actionable.MODULATE));
        assertEquals(0, buffer.extract(key, 1, Actionable.MODULATE));
        assertFalse(buffer.isAllowedIn(1, MATERIAL));
        assertFalse(buffer.tryReplaceSlot(1, null, new GenericStack(MATERIAL, 1)));
        var capture = buffer.beginReserveInsert(buffer.snapshot(), MATERIAL, 1);
        drive(() -> capture.advance(unit()));
        assertEquals(ReservationResult.Status.INVALID_STORAGE, capture.result().status());
        assertThrows(IllegalStateException.class, () -> buffer.restore(Arrays.asList(null, null)));
        assertThrows(IllegalStateException.class, () -> locked(buffer.context(), () -> {
            token.rollback();
            return true;
        }));
        assertEquals(ResourceReservation.Resolution.UNKNOWN, lockedResolution(token));
    }

    private static final class ThrowingHashKey extends AEKey {
        private final int id;
        private final Key delegate;
        private boolean failHash, probing;
        private SlottedResourceBuffer failAfterTotalIn;

        private ThrowingHashKey(int id) {
            this.id = id;
            delegate = new Key(TYPE, "hold_" + id);
        }

        @Override
        public AEKeyType getType() {
            return TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public Object getPrimaryKey() {
            return delegate.getPrimaryKey();
        }

        @Override
        public ResourceLocation getId() {
            return delegate.getId();
        }

        @Override
        public Object getReadOnlyStack() {
            return this;
        }

        @Override
        protected ITextComponent computeDisplayName() {
            return delegate.getDisplayName();
        }

        @Override
        public NBTBase get(String componentId) {
            return null;
        }

        @Override
        public void writeToPacket(PacketBuffer data) {
            delegate.writeToPacket(data);
        }

        @Override
        public NBTTagCompound toTag() {
            return delegate.toTag();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ThrowingHashKey key && id == key.id;
        }

        @Override
        public int hashCode() {
            if (failHash) {
                throw new IllegalStateException("Old key hash callback failed");
            }
            if (failAfterTotalIn != null && !probing) {
                probing = true;
                try {
                    // Observe the real first aggregate write, independently of a fixed hash-call count.
                    if (failAfterTotalIn.amount(this) > 0) {
                        throw new IllegalStateException("Hash failed after first aggregate write");
                    }
                } finally {
                    probing = false;
                }
            }
            return id;
        }
    }
}
