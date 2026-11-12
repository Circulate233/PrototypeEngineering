package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeResourceHatchTest {
    private static final KeyType TYPE = new KeyType("me_resource");
    private static final Key FIRST = new Key(TYPE, "first");
    private static final Key SECOND = new Key(TYPE, "second");

    private static MeResourceHatch hatch(HatchMode mode) {
        return new MeResourceHatch(mode, 2, () -> {}, () -> AEColor.RED, List.of(TYPE));
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    @Test
    void markersRefillNativeLongAmountsWhileOutputHasOneEntryPerKeyAndExportsOnlyUnreservedContents() {
        var hatch = hatch(HatchMode.MIXED);
        var network = new Network();
        network.amounts.addTo(FIRST, 5_000_000_000L);
        assertTrue(hatch.compareInputMark(0, hatch.inputView().revision(), new GenericStack(FIRST, 4_000_000_000L)));
        long markerRevision = hatch.inputView().revision();
        hatch.transfer(network);
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        assertEquals(4_000_000_000L, input.getAmount(0));
        assertEquals(markerRevision, hatch.inputView().revision());
        assertEquals(1_000_000_000L, network.amounts.getLong(FIRST));

        output.insert(FIRST, 3_000_000_000L, Actionable.MODULATE);
        output.insert(FIRST, 2_000_000_000L, Actionable.MODULATE);
        output.insert(SECOND, 5, Actionable.MODULATE);
        assertEquals(2, hatch.outputView().stacks().size());
        var hold = accepted(output.reserveExtract(output.snapshot(), FIRST, 4_000_000_000L));
        hatch.transfer(network);
        assertEquals(4_000_000_000L, output.amount(FIRST));
        assertEquals(0, output.availableAmount(FIRST));
        assertEquals(2_000_000_000L, network.amounts.getLong(FIRST));
        ResourceTransactions.rollback(List.of(hold));
        hatch.transfer(network);
        assertTrue(hatch.outputView().stacks().isEmpty());
        assertEquals(6_000_000_000L, network.amounts.getLong(FIRST));
    }

    @Test
    void editingMarkerReturnsOldMaterialBeforeRefillAndDoesNotDiscardItWhenNetworkIsFull() {
        var hatch = hatch(HatchMode.INPUT);
        var network = new Network();
        var input = Objects.requireNonNull(hatch.input());
        input.insert(0, FIRST, 50, Actionable.MODULATE);
        network.amounts.addTo(SECOND, 100);
        assertTrue(hatch.compareInputMark(0, hatch.inputView().revision(), new GenericStack(SECOND, 40)));
        network.accept = false;
        hatch.transfer(network);
        assertEquals(new GenericStack(FIRST, 50), input.getStack(0));
        assertEquals(100, network.amounts.getLong(SECOND));
        network.accept = true;
        hatch.transfer(network);
        assertEquals(new GenericStack(SECOND, 40), input.getStack(0));
        assertEquals(50, network.amounts.getLong(FIRST));
        assertEquals(60, network.amounts.getLong(SECOND));
    }

    @Test
    void outputReservationsRetainLongHeadroomAndRefundsAcrossPartialNativeTransferAndDestruction() {
        var output = new UnboundedResourceBuffer(() -> {});
        output.insert(FIRST, Long.MAX_VALUE - 10, Actionable.MODULATE);
        var insert = accepted(output.reserveInsert(output.snapshot(), FIRST, 10));
        assertEquals(0, output.insert(FIRST, 1, Actionable.MODULATE));
        assertTrue(ResourceTransactions.commit(List.of(insert)));
        assertEquals(Long.MAX_VALUE, output.amount(FIRST));
        output.context().lock();
        try {
            var receipt = output.reserveTransfer(FIRST, 100, false);
            assertNotNull(receipt);
            assertEquals(0, output.insert(FIRST, 1, Actionable.MODULATE));
            receipt.complete(30);
        } finally {
            output.context().unlock();
        }
        assertEquals(Long.MAX_VALUE - 30, output.amount(FIRST));

        var consume = accepted(output.reserveExtract(output.snapshot(), FIRST, 100));
        output.context().lock();
        try { consume.apply(); }
        finally { output.context().unlock(); }
        var delayed = new ObjectArrayList<GenericStack>();
        output.context().lock();
        try { assertTrue(output.removeForDrops(delayed::addAll).isEmpty()); }
        finally { output.context().unlock(); }
        assertTrue(delayed.isEmpty());
        output.context().lock();
        try { consume.rollback(); }
        finally { output.context().unlock(); }
        assertEquals(List.of(new GenericStack(FIRST, Long.MAX_VALUE - 30)), delayed);
        assertFalse(output.isValid());
        assertEquals(0, output.amount(FIRST));
    }

    @Test
    void outputFromAnEarlierStageFeedsLaterInputsWhileReverseRollbackRestoresTheExactBalance() {
        var output = new UnboundedResourceBuffer(() -> {});
        var produce = accepted(output.reserveInsert(output.snapshot(), FIRST, 100));
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(output.context())));
        try {
            fence.access(() -> {
                produce.apply();
                assertEquals(0, output.availableAmount(FIRST));
                produce.exposeApplied();
                var consume = accepted(output.reserveExtract(output.snapshot(), FIRST, 60));
                consume.apply();
                consume.exposeApplied();
                assertEquals(40, output.amount(FIRST));
                assertThrows(IllegalStateException.class, produce::rollback);
                consume.rollback();
                produce.rollback();
                assertEquals(0, output.amount(FIRST));
            });
        } finally {
            fence.release();
        }
    }

    @Test
    void anUncertainExternalExtractionRetainsItsReceiptAndDoesNotReplayTheNetworkOperation() {
        var hatch = hatch(HatchMode.INPUT);
        assertTrue(hatch.compareInputMark(0, hatch.inputView().revision(), new GenericStack(FIRST, 50)));
        int[] attempts = {0};
        var failing = new MeResourceHatch.MeTransfer() {
            @Override
            public long insert(AEKey key, long amount) {
                throw new AssertionError("No old input should be returned");
            }

            @Override
            public long extract(AEKey key, long amount) {
                attempts[0]++;
                throw new IllegalStateException("External result is unknown after entry");
            }
        };
        assertThrows(IllegalStateException.class, () -> hatch.transfer(failing));
        assertTrue(hatch.hasTransferReservation());
        hatch.transfer(failing);
        assertEquals(1, attempts[0]);
        assertEquals(0, Objects.requireNonNull(hatch.input()).amount(FIRST));
    }

    private static final class Network implements MeResourceHatch.MeTransfer {
        private final AEKey2LongMap amounts = new AEKey2LongMap.OpenHashMap();
        private boolean accept = true;

        @Override
        public long insert(AEKey key, long amount) {
            long accepted = accept ? Math.min(amount, Long.MAX_VALUE - amounts.getLong(key)) : 0;
            amounts.addTo(key, accepted);
            return accepted;
        }

        @Override
        public long extract(AEKey key, long amount) {
            long extracted = Math.min(amount, amounts.getLong(key));
            amounts.addTo(key, -extracted);
            return extracted;
        }
    }
}
