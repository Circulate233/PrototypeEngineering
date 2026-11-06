package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferedResourceHatchTest {
    private static final KeyType FIRST = new KeyType("first");
    private static final KeyType SECOND = new KeyType("second");
    private static final IActionSource SOURCE = IActionSource.empty();

    private BufferedResourceHatch mixed() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, 10_000L);
        capacities.put(SECOND, 20_000L);
        return new BufferedResourceHatch(HatchMode.MIXED, 2,
            capacities, () -> {
        });
    }

    @Test
    void automationAndMachineUseIndependentMultiTypeBuffers() {
        var hatch = mixed();
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var first = new Key(FIRST, "material");
        var second = new Key(SECOND, "material");
        var outside = hatch.automation();

        assertEquals(70, outside.insert(first, 70, Actionable.SIMULATE, SOURCE));
        assertEquals(0, input.amount(first));
        outside.insert(first, 70, Actionable.MODULATE, SOURCE);
        outside.insert(second, 9_000, Actionable.MODULATE, SOURCE);
        output.insert(first, 5, Actionable.MODULATE);
        output.insert(second, 300, Actionable.MODULATE);

        assertEquals(5, outside.getAvailableStacks().get(first));
        assertEquals(5, outside.extract(first, 80, Actionable.MODULATE, SOURCE));
        assertEquals(0, outside.extract(first, 80, Actionable.MODULATE, SOURCE));
        assertEquals(70, input.amount(first));
        assertEquals(20, input.extract(first, 20, Actionable.MODULATE));
        assertEquals(9_000, input.amount(second));
        assertEquals(300, output.amount(second));
    }

    @Test
    void retainedAccessCannotTransferAfterHostInvalidation() {
        var hatch = mixed();
        var key = new Key(FIRST, "material");
        var outside = hatch.automation();
        var machineInput = Objects.requireNonNull(hatch.input());
        var machineOutput = Objects.requireNonNull(hatch.output());
        outside.insert(key, 50, Actionable.MODULATE, SOURCE);
        machineOutput.insert(key, 30, Actionable.MODULATE);
        hatch.invalidate();

        assertEquals(0, outside.insert(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, outside.extract(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, machineInput.extract(key, 10, Actionable.MODULATE));
        assertEquals(0, machineOutput.insert(key, 10, Actionable.MODULATE));
        assertEquals(50, machineInput.getAmount(0));
        assertEquals(30, machineOutput.getAmount(0));
    }

    @Test
    void reservationsProtectBothMachineInputsAndAutomatedOutputExtraction() {
        var hatch = mixed();
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var outside = hatch.automation();
        var key = new Key(FIRST, "held_material");
        input.insert(key, 100, Actionable.MODULATE);
        output.insert(key, 100, Actionable.MODULATE);
        var inputHold = input.reserveExtract(input.snapshot(), key, 70);
        var outputHold = output.reserveExtract(output.snapshot(), key, 70);
        assertEquals(ReservationResult.Status.ACCEPTED, inputHold.status());
        assertEquals(ReservationResult.Status.ACCEPTED, outputHold.status());

        assertEquals(30, input.extract(key, 100, Actionable.SIMULATE));
        assertEquals(30, input.extract(key, 100, Actionable.MODULATE));
        assertEquals(30, outside.extract(key, 100, Actionable.SIMULATE, SOURCE));
        assertEquals(30, outside.extract(key, 100, Actionable.MODULATE, SOURCE));
        assertEquals(0, outside.extract(key, 100, Actionable.MODULATE, SOURCE));
        assertEquals(70, input.amount(key));
        assertEquals(70, output.amount(key));
        assertEquals(0, input.snapshot().available().getLong(key));
        assertTrue(ResourceTransactions.commit(List.of(
            Objects.requireNonNull(inputHold.reservation()), Objects.requireNonNull(outputHold.reservation()))));
        assertEquals(0, input.amount(key));
        assertEquals(0, output.amount(key));
    }

    @Test
    void slotAutomationCannotReverseEitherSideOrReplaceItsKey() {
        var hatch = mixed();
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var port = new HatchInventoryPort(hatch, true, true);
        var key = new Key(FIRST, "material");
        var other = new Key(SECOND, "other");
        assertEquals(70, port.insert(0, key, 70, Actionable.MODULATE));
        output.insert(0, key, 30, Actionable.MODULATE);
        assertEquals(0, port.extract(0, key, 10, Actionable.MODULATE));
        assertEquals(0, port.insert(2, other, 10, Actionable.MODULATE));
        assertThrows(UnsupportedOperationException.class, () -> port.setStack(0, new GenericStack(other, 10)));
        assertThrows(UnsupportedOperationException.class, () -> port.setStack(2, new GenericStack(other, 10)));
        assertEquals(70, input.getAmount(0));
        assertEquals(30, port.extract(2, key, 100, Actionable.SIMULATE));
        assertEquals(30, port.extract(2, key, 100, Actionable.MODULATE));
        hatch.invalidate();
        assertEquals(0, port.insert(1, other, 10, Actionable.MODULATE));
        assertEquals(0, port.extract(2, key, 10, Actionable.MODULATE));
    }

    @Test
    void cursorTransfersConserveLargeStacksAcrossDepositSplitAndOutputCollection() {
        var hatch = mixed();
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var key = new Key(FIRST, "material");
        input.insert(0, key, 9980, Actionable.MODULATE);
        var cursor = Objects.requireNonNull(HatchSlotActions.click(input, 0, new GenericStack(key, 64), 64, true, false));
        assertEquals(44, cursor.amount());
        assertEquals(10000, input.getAmount(0));
        assertNull(HatchSlotActions.click(input, 1, cursor, 64, true, false));
        cursor = Objects.requireNonNull(HatchSlotActions.click(input, 0, null, 64, true, true));
        assertEquals(64, cursor.amount());
        assertEquals(9936, input.getAmount(0));
        output.insert(0, key, 50, Actionable.MODULATE);
        var held = new GenericStack(key, 40);
        held = Objects.requireNonNull(HatchSlotActions.click(output, 0, held, 64, false, false));
        assertEquals(64, held.amount());
        assertEquals(26, output.getAmount(0));
        assertEquals(40 + 50, held.amount() + output.getAmount(0));
        assertEquals(9980 + 64, cursor.amount() + input.getAmount(0) + input.getAmount(1));
    }

    @Test
    void keySwapRequiresBothSidesToFitAndLeavesRejectedTransactionsUntouched() {
        var hatch = mixed();
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var first = new Key(FIRST, "first");
        var second = new Key(SECOND, "second");
        input.insert(0, first, 100, Actionable.MODULATE);
        var cursor = new GenericStack(second, 16);
        assertEquals(cursor, HatchSlotActions.click(input, 0, cursor, 64, true, false));
        assertEquals(100, input.getAmount(0));
        input.extract(0, first, 80, Actionable.MODULATE);
        var swapped = HatchSlotActions.click(input, 0, cursor, 64, true, false);
        assertEquals(new GenericStack(first, 20), swapped);
        assertEquals(cursor, input.getStack(0));
        output.insert(0, first, 20, Actionable.MODULATE);
        assertEquals(cursor, HatchSlotActions.click(output, 0, cursor, 64, false, false));
        assertEquals(new GenericStack(first, 20), output.getStack(0));
    }

    @Test
    void overflowingDecodedOutputCannotReplaceTheInputSideFirst() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, Long.MAX_VALUE);
        capacities.put(SECOND, Long.MAX_VALUE);
        var hatch = new BufferedResourceHatch(HatchMode.MIXED, 2, capacities, () -> {
        });
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var first = new Key(FIRST, "saved_first");
        var second = new Key(SECOND, "saved_second");
        input.insert(first, 17, Actionable.MODULATE);
        output.insert(second, 19, Actionable.MODULATE);
        var nextInput = new ObjectArrayList<GenericStack>();
        nextInput.size(2);
        nextInput.set(0, new GenericStack(first, 31));
        var overflowingOutput = List.of(new GenericStack(second, Long.MAX_VALUE), new GenericStack(second, 1));

        assertThrows(IllegalArgumentException.class, () -> hatch.restoreContents(nextInput, overflowingOutput));
        assertEquals(17, input.amount(first));
        assertEquals(19, output.amount(second));
        assertNull(input.getStack(1));
        assertNull(output.getStack(1));
    }

    @Test
    void aReservationOnEitherSideRejectsDecodedReloadWithoutChangingTheOtherSide() {
        var hatch = mixed();
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var first = new Key(FIRST, "saved_first");
        var second = new Key(SECOND, "saved_second");
        input.insert(first, 30, Actionable.MODULATE);
        output.insert(second, 60, Actionable.MODULATE);
        var nextInput = new ObjectArrayList<GenericStack>();
        nextInput.size(2);
        nextInput.set(0, new GenericStack(first, 40));
        var nextOutput = new ObjectArrayList<GenericStack>();
        nextOutput.size(2);
        nextOutput.set(0, new GenericStack(second, 70));
        var inputHold = input.reserveExtract(input.snapshot(), first, 10);
        assertEquals(ReservationResult.Status.ACCEPTED, inputHold.status());
        assertThrows(IllegalStateException.class, () -> hatch.restoreContents(nextInput, nextOutput));
        assertEquals(30, input.amount(first));
        assertEquals(20, input.availableAmount(first));
        assertEquals(60, output.amount(second));
        ResourceTransactions.rollback(List.of(Objects.requireNonNull(inputHold.reservation())));

        var outputHold = output.reserveInsert(output.snapshot(), second, 20);
        assertEquals(ReservationResult.Status.ACCEPTED, outputHold.status());
        assertThrows(IllegalStateException.class, () -> hatch.restoreContents(nextInput, nextOutput));
        assertEquals(30, input.amount(first));
        assertEquals(60, output.amount(second));
        ResourceTransactions.rollback(List.of(Objects.requireNonNull(outputHold.reservation())));
        hatch.restoreContents(nextInput, nextOutput);
        assertEquals(40, input.amount(first));
        assertEquals(70, output.amount(second));
    }

}
