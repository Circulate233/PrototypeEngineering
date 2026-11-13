package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlottedResourceHatchExtensionTest {
    private static final KeyType TARGET = new KeyType("clear_target");
    private static final KeyType OTHER = new KeyType("clear_other");

    private static ExternalHatch hatch() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TARGET, 1_000);
        capacities.put(OTHER, 1_000);
        return new ExternalHatch(new SlottedResourceBuffer(3, capacities, () -> {
        }),
            new SlottedResourceBuffer(2, capacities, () -> {
            }));
    }

    @Test
    void sharedComputationSlotsExposeBothDirectionsOnceAndHonorFiltersAndReservations() {
        var card = new Key(TARGET, "card");
        var rejected = new Key(TARGET, "ordinary_item");
        var fluid = new Key(OTHER, "fluid");
        for (int tier = 1; tier <= 4; tier++) {
            long amount = BufferedComputationHatch.nonItemCapacity(tier);
            var capacities = new Reference2LongArrayMap<AEKeyType>();
            capacities.put(TARGET, 1);
            capacities.put(OTHER, amount);
            var storage = new SlottedResourceBuffer(tier, capacities, key -> !key.equals(rejected), () -> {
            });
            var shared = new ExternalHatch(storage, storage);
            var port = new HatchInventoryPort(shared, true, true);
            assertEquals(tier, port.size());
            assertEquals(0, port.insert(0, rejected, 1, Actionable.MODULATE));
            assertEquals(1, port.insert(0, card, 64, Actionable.MODULATE));
            assertEquals(0, port.insert(0, card, 1, Actionable.MODULATE));
            assertEquals(1, port.extract(0, card, 64, Actionable.MODULATE));
            assertEquals(amount, port.insert(0, fluid, amount + 1, Actionable.MODULATE));
            var reserved = Objects.requireNonNull(storage.reserveExtract(storage.snapshot(), fluid, 1_000).reservation());
            assertEquals(amount - 1_000, port.extract(0, fluid, amount, Actionable.MODULATE));
            assertTrue(ResourceTransactions.commit(List.of(reserved)));
            assertEquals(0, storage.amount(fluid));
        }
    }

    @Test
    void externalAsymmetricHatchUsesSharedAdaptersAndClearsOnlyUnreservedTargetContents() {
        var hatch = hatch();
        var first = new Key(TARGET, "first");
        var second = new Key(TARGET, "second");
        var unrelated = new Key(OTHER, "unrelated");
        var port = new HatchInventoryPort(hatch, true, true);
        assertEquals(100, port.insert(0, first, 100, Actionable.MODULATE));
        assertEquals(9, port.insert(1, unrelated, 9, Actionable.MODULATE));
        hatch.output().insert(0, second, 90, Actionable.MODULATE);
        hatch.output().insert(1, unrelated, 11, Actionable.MODULATE);
        var input = Objects.requireNonNull(hatch.input().reserveExtract(hatch.input().snapshot(), first, 40).reservation());
        var output = Objects.requireNonNull(hatch.output().reserveExtract(hatch.output().snapshot(), second, 60).reservation());

        assertEquals(30, port.getAmount(3));
        assertEquals(0, port.extract(0, first, 100, Actionable.MODULATE));
        assertEquals(0, port.insert(3, first, 100, Actionable.MODULATE));
        assertTrue(HatchSlotActions.clearAvailable(hatch.input(), TARGET));
        assertTrue(HatchSlotActions.clearAvailable(hatch.output(), TARGET));
        assertEquals(40, hatch.input().amount(first));
        assertEquals(60, hatch.output().amount(second));
        assertEquals(9, hatch.input().amount(unrelated));
        assertEquals(11, hatch.output().amount(unrelated));
        assertTrue(ResourceTransactions.commit(List.of(input, output)));
        assertEquals(0, hatch.input().amount(first));
        assertEquals(0, hatch.output().amount(second));
        assertEquals(11, port.extract(4, unrelated, 100, Actionable.MODULATE));
    }

    @Test
    void clearingCommittedContentsPreservesAppliedOutputAndInputRefundRights() {
        var hatch = hatch();
        var key = new Key(TARGET, "material");
        hatch.input().insert(0, key, 80, Actionable.MODULATE);
        hatch.output().insert(0, key, 30, Actionable.MODULATE);
        var input = Objects.requireNonNull(hatch.input().reserveExtract(hatch.input().snapshot(), key, 50).reservation());
        var output = Objects.requireNonNull(hatch.output().reserveInsert(hatch.output().snapshot(), key, 50).reservation());
        hatch.input().context().lock();
        hatch.output().context().lock();
        try {
            input.apply();
            output.apply();
            assertTrue(HatchSlotActions.clearAvailable(hatch.input(), TARGET));
            assertTrue(HatchSlotActions.clearAvailable(hatch.output(), TARGET));
            assertEquals(0, hatch.input().amount(key));
            assertEquals(50, hatch.output().amount(key));
            output.rollback();
            input.rollback();
            assertEquals(50, hatch.input().amount(key));
            assertEquals(0, hatch.output().amount(key));
        } finally {
            hatch.output().context().unlock();
            hatch.input().context().unlock();
        }
    }

    @Test
    void invalidExternalStorageRejectsClearingAndRetainedAutomationReferences() {
        var hatch = hatch();
        var key = new Key(TARGET, "material");
        var port = new HatchInventoryPort(hatch, true, true);
        port.insert(0, key, 25, Actionable.MODULATE);
        hatch.input().context().invalidate();
        assertFalse(HatchSlotActions.clearAvailable(hatch.input(), TARGET));
        assertEquals(25, hatch.input().getAmount(0));
        assertEquals(0, port.insert(0, key, 10, Actionable.MODULATE));
    }

    @Test
    void retainedFenceRejectsOwnerClearUntilItsPendingReservationIsSettled() {
        var hatch = hatch();
        var key = new Key(TARGET, "pending");
        hatch.input().insert(0, key, 50, Actionable.MODULATE);
        var reservation = Objects.requireNonNull(hatch.input().reserveExtract(hatch.input().snapshot(), key, 30).reservation());
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(hatch.input().context())));
        try {
            assertFalse(HatchSlotActions.clearAvailable(hatch.input(), TARGET));
            fence.access(() -> {
                assertEquals(50, hatch.input().amount(key));
                reservation.rollback();
            });
        } finally {
            fence.release();
        }
        assertTrue(HatchSlotActions.clearAvailable(hatch.input(), TARGET));
        assertEquals(0, hatch.input().amount(key));
    }

    private record ExternalHatch(SlottedResourceStorage input,
                                 SlottedResourceStorage output) implements SlottedResourceHatch {
        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }
    }
}
