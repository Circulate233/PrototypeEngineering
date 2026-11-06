package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(0, input.getAvailableStacks().get(first));
        outside.insert(first, 70, Actionable.MODULATE, SOURCE);
        outside.insert(second, 9_000, Actionable.MODULATE, SOURCE);
        output.insert(first, 5, Actionable.MODULATE, SOURCE);
        output.insert(second, 300, Actionable.MODULATE, SOURCE);

        assertEquals(5, outside.getAvailableStacks().get(first));
        assertEquals(5, outside.extract(first, 80, Actionable.MODULATE, SOURCE));
        assertEquals(0, outside.extract(first, 80, Actionable.MODULATE, SOURCE));
        assertEquals(70, input.getAvailableStacks().get(first));
        assertEquals(20, input.extract(first, 20, Actionable.MODULATE, SOURCE));
        assertEquals(9_000, input.getAvailableStacks().get(second));
        assertEquals(300, output.getAvailableStacks().get(second));
    }

    @Test
    void retainedAccessCannotTransferAfterHostInvalidation() {
        var hatch = mixed();
        var key = new Key(FIRST, "material");
        var outside = hatch.automation();
        var machineInput = Objects.requireNonNull(hatch.input());
        var machineOutput = Objects.requireNonNull(hatch.output());
        outside.insert(key, 50, Actionable.MODULATE, SOURCE);
        machineOutput.insert(key, 30, Actionable.MODULATE, SOURCE);
        hatch.invalidate();

        assertEquals(0, outside.insert(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, outside.extract(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, machineInput.extract(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, machineOutput.insert(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(50, machineInput.getAmount(0));
        assertEquals(30, machineOutput.getAmount(0));
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

}
