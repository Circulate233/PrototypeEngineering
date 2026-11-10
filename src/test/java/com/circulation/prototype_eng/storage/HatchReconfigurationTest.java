package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchReconfigurationTest {
    private static final KeyType FIRST = new KeyType("reconfiguration_first");
    private static final KeyType SECOND = new KeyType("reconfiguration_second");
    private static final Key A = new Key(FIRST, "a");
    private static final Key B = new Key(SECOND, "b");

    private static ResourceHatchConfiguration profile(int slots, long capacity) {
        return ResourceHatchConfiguration.symmetric(HatchMode.MIXED, slots, Map.of(FIRST, capacity, SECOND, capacity));
    }

    private static String label(StorageContext context) {
        context.lock();
        try {
            return context.sourceIdentity();
        } finally {
            context.unlock();
        }
    }

    @Test
    void actualOldFencePreservesBothBalancesAndPhysicalLabelsWithNewContexts() {
        var old = new BufferedResourceHatch(profile(3, 10), () -> {
        });
        old.bindSourceIdentities("test:resource/1/2/3");
        var input = Objects.requireNonNull(old.input());
        var output = Objects.requireNonNull(old.output());
        input.insert(0, A, 8, Actionable.MODULATE);
        input.insert(2, A, 7, Actionable.MODULATE);
        output.insert(B, 27, Actionable.MODULATE);
        var oldPort = new HatchInventoryPort(old, true, true);
        var target = new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(96, Map.of(FIRST, 6L)),
            new ResourceHatchConfiguration.Side(128, Map.of(SECOND, 100L)));
        var installed = new BufferedResourceHatch[]{old};
        var result = old.replace(target, "test:resource/1/2/3", () -> {
            }, () -> installed[0] == old,
            candidate -> () -> installed[0] = candidate);
        assertEquals(HatchReconfigurationStatus.APPLIED, result.status());
        var next = installed[0];
        var nextInput = Objects.requireNonNull(next.input());
        var nextOutput = Objects.requireNonNull(next.output());
        assertEquals(15, nextInput.amount(A));
        assertEquals(27, nextOutput.amount(B));
        assertNotSame(input.context(), nextInput.context());
        assertEquals("test:resource/1/2/3/input", label(nextInput.context()));
        assertEquals("test:resource/1/2/3/output", label(nextOutput.context()));
        assertEquals(0, oldPort.insert(0, A, 1, Actionable.MODULATE));
        assertEquals(0, oldPort.extract(3, B, 1, Actionable.MODULATE));
        assertEquals(6, nextInput.insert(95, A, 6, Actionable.MODULATE));
        assertEquals(100, nextOutput.insert(127, B, 100, Actionable.MODULATE));
        var port = new HatchInventoryPort(next, true, true);
        assertTrue(port.isSupportedType(SECOND));
        assertEquals(100, port.getCapacity(SECOND));
        assertEquals(0, port.getCapacity(95, SECOND));
        assertEquals(100, port.getCapacity(223, SECOND));
        assertEquals(100, port.extract(223, B, 100, Actionable.MODULATE));
        assertEquals(27, nextOutput.amount(B));
    }

    @Test
    void capacityRefusalIsAtomicAndOnlyChangedOldVersionsNeedAnotherPreflight() {
        var old = new BufferedResourceHatch(profile(2, 20), () -> {
        });
        var input = Objects.requireNonNull(old.input());
        var output = Objects.requireNonNull(old.output());
        input.insert(A, 9, Actionable.MODULATE);
        output.insert(B, 21, Actionable.MODULATE);
        var refused = old.replace(profile(1, 20), null, () -> {
            }, () -> true,
            _ -> {
                throw new AssertionError("Refused replacement must not prepare publication");
            });
        assertEquals(HatchReconfigurationStatus.BLOCKED_CAPACITY, refused.status());
        assertTrue(refused.unchanged());
        assertTrue(old.isValid());
        assertEquals(9, input.amount(A));
        assertEquals(21, output.amount(B));
        output.extract(B, 1, Actionable.MODULATE);
        assertFalse(refused.unchanged());
        var installed = new BufferedResourceHatch[]{old};
        assertEquals(HatchReconfigurationStatus.APPLIED, old.replace(profile(1, 20), null, () -> {
            }, () -> true,
            candidate -> () -> installed[0] = candidate).status());
        assertEquals(9, Objects.requireNonNull(installed[0].input()).amount(A));
        assertEquals(20, Objects.requireNonNull(installed[0].output()).amount(B));
    }

    @Test
    void activeReservationsAndDeferredMutationBlockReplacementAndPreparationFailureReleasesFence() {
        var old = new BufferedResourceHatch(profile(2, 20), () -> {
        });
        var input = Objects.requireNonNull(old.input());
        input.insert(A, 12, Actionable.MODULATE);
        var held = Objects.requireNonNull(input.reserveExtract(input.snapshot(), A, 5).reservation());
        assertEquals(HatchReconfigurationStatus.PENDING_BUSY, old.replace(profile(3, 20), null, () -> {
            }, () -> true,
            _ -> {
                throw new AssertionError("Reserved balance must not publish");
            }).status());
        ResourceTransactions.rollback(List.of(held));
        input.context().lock();
        try {
            input.context().beginDeferredMutation();
        } finally {
            input.context().unlock();
        }
        assertEquals(HatchReconfigurationStatus.PENDING_BUSY, old.replace(profile(3, 20), null, () -> {
            }, () -> true,
            _ -> {
                throw new AssertionError("Unclosed mutation must not publish");
            }).status());
        input.context().lock();
        try {
            input.context().endDeferredMutation();
        } finally {
            input.context().unlock();
        }
        assertThrows(IllegalArgumentException.class, () -> old.replace(profile(3, 20), "test:failure", () -> {
            }, () -> true,
            _ -> {
                throw new IllegalArgumentException("Prepared host adapter failed");
            }));
        assertTrue(old.isValid());
        assertEquals(12, input.amount(A));
        assertEquals(1, input.extract(A, 1, Actionable.MODULATE));
        var contexts = List.of(input.context(), Objects.requireNonNull(old.output()).context());
        var retained = Objects.requireNonNull(StorageFence.tryAcquire(contexts));
        try {
            assertEquals(HatchReconfigurationStatus.PENDING_BUSY, old.replace(profile(3, 20), null, () -> {
                }, () -> true,
                _ -> {
                    throw new AssertionError("Retained original fence blocks admission");
                }).status());
        } finally {
            retained.release();
        }
        assertEquals(11, input.amount(A));
    }

    @Test
    void nativeItemMetadataUsesTheSlotSideForDifferentCapacities() {
        var items = AEKeyType.items();
        var profile = new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(96, Map.of(items, 64L)),
            new ResourceHatchConfiguration.Side(128, Map.of(items, 1024L, SECOND, 2000L)));
        var hatch = new BufferedResourceHatch(profile, () -> {
        });
        var port = new HatchInventoryPort(hatch, true, true);
        var nativeItems = new HatchItemHandler(port);
        assertEquals(64, nativeItems.getSlotLimit(95));
        assertEquals(1024, nativeItems.getSlotLimit(223));
        Objects.requireNonNull(hatch.output()).insert(127, B, 777, Actionable.MODULATE);
        assertEquals(777, port.extract(223, B, 800, Actionable.MODULATE));
        assertEquals(2000, port.getCapacity(SECOND));
    }

    @Test
    void expansionPreservesLegalSlotPositionsAndSameKeyDistribution() {
        var old = new BufferedResourceHatch(profile(3, 10), () -> {
        });
        var input = Objects.requireNonNull(old.input());
        var output = Objects.requireNonNull(old.output());
        input.insert(0, A, 2, Actionable.MODULATE);
        input.insert(2, A, 7, Actionable.MODULATE);
        output.insert(2, B, 8, Actionable.MODULATE);
        var installed = new BufferedResourceHatch[]{old};
        assertEquals(HatchReconfigurationStatus.APPLIED, old.replace(profile(4, 20), null, () -> {
            }, () -> true,
            candidate -> () -> installed[0] = candidate).status());
        var nextInput = Objects.requireNonNull(installed[0].input());
        var nextOutput = Objects.requireNonNull(installed[0].output());
        assertEquals(2, nextInput.getAmount(0));
        assertNull(nextInput.getStack(1));
        assertEquals(7, nextInput.getAmount(2));
        assertNull(nextInput.getStack(3));
        assertNull(nextOutput.getStack(0));
        assertEquals(8, nextOutput.getAmount(2));
    }

    @Test
    void shrinkingMovesOnlyOverflowAndCompactsOnlyWhenFragmentationRequiresIt() {
        var old = new BufferedResourceHatch(profile(4, 20), () -> {
        });
        var input = Objects.requireNonNull(old.input());
        var output = Objects.requireNonNull(old.output());
        input.insert(1, A, 15, Actionable.MODULATE);
        input.insert(3, B, 5, Actionable.MODULATE);
        output.insert(0, A, 2, Actionable.MODULATE);
        output.insert(1, A, 3, Actionable.MODULATE);
        output.insert(3, B, 7, Actionable.MODULATE);
        var target = new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(3, Map.of(FIRST, 10L, SECOND, 10L)),
            new ResourceHatchConfiguration.Side(2, Map.of(FIRST, 10L, SECOND, 10L)));
        var installed = new BufferedResourceHatch[]{old};
        assertEquals(HatchReconfigurationStatus.APPLIED, old.replace(target, null, () -> {
            }, () -> true,
            candidate -> () -> installed[0] = candidate).status());
        var nextInput = Objects.requireNonNull(installed[0].input());
        var nextOutput = Objects.requireNonNull(installed[0].output());
        assertEquals(10, nextInput.getAmount(1)); // Original in-range slot keeps the allowed prefix.
        assertEquals(15, nextInput.amount(A));
        assertEquals(5, nextInput.amount(B));
        assertEquals(A, Objects.requireNonNull(nextOutput.getStack(0)).what());
        assertEquals(5, nextOutput.getAmount(0)); // Compaction is necessary to free the second slot for B.
        assertEquals(B, Objects.requireNonNull(nextOutput.getStack(1)).what());
        assertEquals(7, nextOutput.getAmount(1));
    }
}
