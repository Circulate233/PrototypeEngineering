package com.circulation.prototype_eng.storage;

import ae2.api.stacks.AEKeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchDestructionTest {
    private static final KeyType FIRST = new KeyType("destruction_first");
    private static final KeyType SECOND = new KeyType("destruction_second");
    private static final Key A = new Key(FIRST, "a");
    private static final Key B = new Key(SECOND, "b");

    private static BufferedResourceHatch hatch(HatchMode mode) {
        return new BufferedResourceHatch(mode, 3, new Reference2LongArrayMap<>(new AEKeyType[]{FIRST, SECOND}, new long[]{Long.MAX_VALUE, 1000L}), () -> { });
    }

    private static SlottedResourceBuffer input(BufferedResourceHatch hatch) {
        return (SlottedResourceBuffer) Objects.requireNonNull(hatch.input());
    }

    private static SlottedResourceBuffer output(BufferedResourceHatch hatch) {
        return (SlottedResourceBuffer) Objects.requireNonNull(hatch.output());
    }

    private static void apply(ResourceReservation token) {
        token.context().lock();
        try { token.apply(); }
        finally { token.context().unlock(); }
    }

    private static void settle(ResourceReservation token, boolean complete) {
        token.context().lock();
        try {
            if (complete) token.complete();
            else token.rollback();
        } finally { token.context().unlock(); }
    }

    @Test
    void allThreeDirectionsEjectEveryResourceOnceAndRetireOldViews() {
        for (var mode : HatchMode.values()) {
            var hatch = hatch(mode);
            var expected = new ObjectArrayList<GenericStack>();
            var first = mode.hasInput() ? input(hatch) : output(hatch);
            first.insert(0, A, Long.MAX_VALUE, Actionable.MODULATE);
            first.insert(2, B, 999, Actionable.MODULATE);
            expected.add(new GenericStack(A, Long.MAX_VALUE));
            expected.add(new GenericStack(B, 999));
            if (mode == HatchMode.MIXED) {
                output(hatch).insert(0, A, Long.MAX_VALUE, Actionable.MODULATE);
                expected.add(new GenericStack(A, Long.MAX_VALUE));
            }
            var delayed = new ObjectArrayList<GenericStack>();
            assertEquals(expected, hatch.removeForDrops(delayed::addAll));
            assertFalse(hatch.isValid());
            assertTrue(delayed.isEmpty());
            assertTrue(hatch.removeForDrops(delayed::addAll).isEmpty());
            for (var side : mode == HatchMode.MIXED ? List.of(input(hatch), output(hatch))
                : List.of(mode.hasInput() ? input(hatch) : output(hatch))) {
                for (int slot = 0; slot < side.size(); slot++) assertNull(side.getStack(slot));
                assertEquals(0, side.insert(A, 1, Actionable.MODULATE));
                assertEquals(0, side.extract(A, 1, Actionable.MODULATE));
            }
        }
    }

    @Test
    void pendingInputCannotApplyAfterRemovalAndRollbackEjectsItsUnconsumedBalance() {
        var hatch = hatch(HatchMode.INPUT);
        var input = input(hatch);
        input.insert(A, 100, Actionable.MODULATE);
        var token = Objects.requireNonNull(input.reserveExtract(input.snapshot(), A, 70).reservation());
        var delayed = new ObjectArrayList<GenericStack>();
        assertTrue(hatch.removeForDrops(delayed::addAll).isEmpty());
        token.context().lock();
        try {
            assertFalse(token.canApply());
            assertThrows(IllegalStateException.class, token::apply);
        } finally { token.context().unlock(); }
        settle(token, false);
        assertEquals(List.of(new GenericStack(A, 100)), delayed);
        assertNull(input.getStack(0));
        assertTrue(hatch.removeForDrops(delayed::addAll).isEmpty());
    }

    @Test
    void appliedInputKeepsRefundRightsAndOnlyDropsTheDecidedBalance() {
        for (boolean complete : List.of(false, true)) {
            var hatch = hatch(HatchMode.INPUT);
            var input = input(hatch);
            input.insert(A, 100, Actionable.MODULATE);
            var token = Objects.requireNonNull(input.reserveExtract(input.snapshot(), A, 70).reservation());
            apply(token);
            var delayed = new ObjectArrayList<GenericStack>();
            assertTrue(hatch.removeForDrops(delayed::addAll).isEmpty());
            assertTrue(delayed.isEmpty());
            settle(token, complete);
            assertEquals(List.of(new GenericStack(A, complete ? 30 : 100)), delayed);
            assertNull(input.getStack(0));
        }
    }

    @Test
    void provisionalOutputIsNotDroppedBeforeItsCommitOrCompensation() {
        for (boolean complete : List.of(false, true)) {
            var hatch = hatch(HatchMode.OUTPUT);
            var output = output(hatch);
            output.insert(A, 30, Actionable.MODULATE);
            var token = Objects.requireNonNull(output.reserveInsert(output.snapshot(), A, 40).reservation());
            apply(token);
            var delayed = new ObjectArrayList<GenericStack>();
            assertTrue(hatch.removeForDrops(delayed::addAll).isEmpty());
            assertTrue(delayed.isEmpty());
            settle(token, complete);
            assertEquals(List.of(new GenericStack(A, complete ? 70 : 30)), delayed);
        }
    }

    @Test
    void independentSideDropsImmediatelyWhileTheOtherWaitsForItsLastToken() {
        var hatch = hatch(HatchMode.MIXED);
        var input = input(hatch);
        input.insert(A, 100, Actionable.MODULATE);
        output(hatch).insert(A, 60, Actionable.MODULATE);
        var first = Objects.requireNonNull(input.reserveExtract(input.snapshot(), A, 20).reservation());
        var second = Objects.requireNonNull(input.reserveExtract(input.snapshot(), A, 30).reservation());
        var delayed = new ObjectArrayList<GenericStack>();
        assertEquals(List.of(new GenericStack(A, 60)), hatch.removeForDrops(delayed::addAll));
        settle(first, false);
        assertTrue(delayed.isEmpty());
        settle(second, false);
        assertEquals(List.of(new GenericStack(A, 100)), delayed);
    }

    @Test
    void externalReentryRemovalPreservesPartialInputAndOutputReceipts() {
        for (boolean insert : List.of(false, true)) {
            var hatch = hatch(insert ? HatchMode.INPUT : HatchMode.OUTPUT);
            var side = insert ? input(hatch) : output(hatch);
            if (!insert) side.insert(A, 100, Actionable.MODULATE);
            NativeTransferReservation receipt;
            side.context().lock();
            try { receipt = Objects.requireNonNull(side.reserveTransfer(A, 60, insert)); }
            finally { side.context().unlock(); }
            var delayed = new ObjectArrayList<GenericStack>();
            assertTrue(hatch.removeForDrops(delayed::addAll).isEmpty());
            side.context().lock();
            try { receipt.complete(25); }
            finally { side.context().unlock(); }
            assertEquals(List.of(new GenericStack(A, insert ? 25 : 75)), delayed);
            assertNull(side.getStack(0));
        }
    }

    @Test
    void workerSettlementPublishesAfterLocksAndReentrantRemovalCannotDuplicateDrops() throws Exception {
        var hatch = hatch(HatchMode.MIXED);
        var input = input(hatch);
        input.insert(A, 100, Actionable.MODULATE);
        var token = Objects.requireNonNull(input.reserveExtract(input.snapshot(), A, 70).reservation());
        apply(token);
        var delayed = new ObjectArrayList<GenericStack>();
        assertTrue(hatch.removeForDrops(contents -> {
            // lockAll rejects callbacks made inside an existing physical/fence scope.
            assertTrue(hatch.removeForDrops(delayed::addAll).isEmpty());
            delayed.addAll(contents);
        }).isEmpty());
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> settle(token, false)).get();
        }
        assertEquals(List.of(new GenericStack(A, 100)), delayed);
    }
}
