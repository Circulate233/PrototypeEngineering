package com.circulation.prototype_eng.container;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.storage.HatchGuiInventory;
import com.circulation.prototype_eng.storage.HatchSlotActions;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import io.netty.buffer.Unpooled;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceHatchWindowTest {
    private static final KeyType FIRST = new KeyType("window_first");
    private static final KeyType SECOND = new KeyType("window_second");
    private static final Key ITEM = new Key(FIRST, "material");
    private static final Key FLUID = new Key(SECOND, "fluid");

    private static ResourceHatchBulkTransfer.HeldContainers containerActions(boolean acceptsFill, SlottedResourceStorage storage, long[] filled) {
        return new ResourceHatchBulkTransfer.HeldContainers() {
            @Override
            public boolean isItem(AEKey key) {
                return key.getType() == FIRST;
            }

            @Override
            public boolean canInsertCursor() {
                return true;
            }

            @Override
            public boolean tryEmpty() {
                return false;
            }

            @Override
            public boolean tryFill(GenericStack selected) {
                if (!acceptsFill) return false;
                filled[0] = storage.extract(0, selected.what(), Math.min(100, selected.amount()), Actionable.MODULATE);
                return true;
            }
        };
    }

    private static SlottedResourceBuffer buffer(int slots, Map<AEKeyType, Long> capacities) {
        return new SlottedResourceBuffer(slots, capacities, () -> {
        });
    }

    private static SlottedResourceHatch hatch(SlottedResourceStorage input, @Nullable SlottedResourceStorage output) {
        return new SlottedResourceHatch() {
            @Override
            public SlottedResourceStorage input() {
                return input;
            }

            @Override
            public @Nullable SlottedResourceStorage output() {
                return output;
            }

            @Override
            public boolean isValid() {
                return input.isValid() && (output == null || output.isValid());
            }
        };
    }

    @Test
    void lastLogicalSlotAcceptsClicksAndAnOldViewCannotRetargetTheReusedCell() {
        var input = buffer(40_001, Map.of(FIRST, 10_000L));
        input.insert(0, ITEM, 10, Actionable.MODULATE);
        input.insert(40_000, ITEM, 1_000, Actionable.MODULATE);
        var window = new ResourceHatchWindow(hatch(input, null), 4, 3);
        var old = window.capture();
        assertTrue(window.move(old.revision(), 9_998, 0));
        var last = window.capture();
        assertEquals(40_000, last.input().logicalSlot(8));
        assertEquals(-1, last.input().logicalSlot(9));
        assertEquals(9, last.input().entries().size());
        assertEquals(2, last.input().usedSlots());
        GenericStack[] carried = {null};
        assertFalse(window.interact(old.revision(), true, 0,
            storage -> carried[0] = HatchSlotActions.click(storage, 0, null, 64, true, false)));
        assertFalse(window.interact(old.revision(), true, 40_000,
            storage -> carried[0] = HatchSlotActions.click(storage, 40_000, null, 64, true, false)));
        assertNull(carried[0]);
        assertTrue(window.interact(last.revision(), true, 40_000,
            storage -> carried[0] = HatchSlotActions.click(storage, 40_000, null, 64, true, false)));
        assertEquals(new GenericStack(ITEM, 64), carried[0]);
        assertEquals(936, input.getAmount(40_000));
        assertEquals(10, input.getAmount(0));
        assertFalse(window.interact(last.revision(), true, 40_001, ignored -> {
            throw new AssertionError("Tail cell dispatched");
        }));
    }

    @Test
    void asymmetricSidesKeepIndependentAmountsAndEveryKeyType() {
        var input = buffer(40_003, Map.of(FIRST, 100L, SECOND, 3_000_000_000L));
        var output = buffer(130, Map.of(SECOND, 9_000_000_000L));
        input.insert(40_002, FLUID, 2_500_000_000L, Actionable.MODULATE);
        output.insert(129, FLUID, 8_000_000_000L, Actionable.MODULATE);
        var window = new ResourceHatchWindow(hatch(input, output), 5, 7);
        assertTrue(window.move(window.capture().revision(), 7_994, 19));
        var page = window.capture();
        assertEquals(35, page.output().entries().size());
        assertEquals(3_000_000_000L, page.input().entries().get(32).capacity());
        assertEquals(9_000_000_000L, page.output().entries().get(34).capacity());
        long[] moved = {0};
        assertTrue(window.interact(page.revision(), false, 129,
            storage -> moved[0] = storage.extract(129, FLUID, 2_000_000_000L, Actionable.MODULATE)));
        assertEquals(2_000_000_000L, moved[0]);
        assertEquals(6_000_000_000L, output.getAmount(129));
        assertEquals(2_500_000_000L, input.getAmount(40_002));
        assertFalse(window.move(page.revision(), 7_995, 19));
        assertFalse(window.move(page.revision(), 7_994, 20));
    }

    @Test
    void wholeSideInsertionCanReachTheLastSlotWhileTheFirstWindowIsVisible() {
        var input = buffer(40_001, Map.of(FIRST, 1L));
        assertEquals(40_000, input.insert(ITEM, 40_000, Actionable.MODULATE));
        var window = new ResourceHatchWindow(hatch(input, null), 9, 7);
        var page = window.capture();
        long[] inserted = {0};
        assertTrue(window.interact(page.revision(), true, 0,
            storage -> inserted[0] = storage.insert(ITEM, 64, Actionable.MODULATE)));
        assertEquals(1, inserted[0]);
        assertEquals(1, input.getAmount(40_000));
        assertEquals(40_001, window.capture().input().usedSlots());
        assertEquals(72, window.capture().input().entries().size());
    }

    @Test
    void unversionedNativeUpdatesCannotOverwriteAWindowAndRetiredStorageCannotReceiveClicks() {
        var input = buffer(100, Map.of(FIRST, 1_000L));
        input.insert(0, ITEM, 700, Actionable.MODULATE);
        var window = new ResourceHatchWindow(hatch(input, null), 4, 3);
        ResourceHatchWindowSnapshot[] display = {window.capture()};
        var nativeDisplay = new HatchGuiInventory(16, true, cell -> display[0].input().stack(cell));
        nativeDisplay.setItemDirect(0, ItemStack.EMPTY);
        assertEquals(700, nativeDisplay.getDisplayAmount(0));
        assertTrue(window.move(display[0].revision(), 20, 0));
        display[0] = window.capture();
        nativeDisplay.setItemDirect(0, ItemStack.EMPTY);
        assertNull(nativeDisplay.getDisplayKey(0));
        input.context().lock();
        try {
            input.context().invalidate();
        } finally {
            input.context().unlock();
        }
        assertFalse(window.interact(display[0].revision(), true, 80, ignored -> {
            throw new AssertionError("Retired storage dispatched");
        }));
    }

    @Test
    void windowCodecRejectsAnUnboundedCountBeforeAllocatingEntries() {
        var buffer = Unpooled.buffer();
        try {
            var packet = new PacketBuffer(buffer);
            packet.writeLong(1);
            packet.writeVarInt(0).writeVarInt(Integer.MAX_VALUE).writeVarInt(0).writeVarInt(0);
            packet.writeByte(255);
            assertThrows(IllegalArgumentException.class, () -> new ResourceHatchWindowSnapshot(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void doubleTapInsertionCommitsCursorThenInventoryAndPreservesOverflowAndOtherKeys() {
        var input = buffer(1, Map.of(FIRST, 100L));
        var window = new ResourceHatchWindow(hatch(input, null), 4, 3);
        var player = new PlayerStacks();
        player.stacks[0] = new GenericStack(ITEM, 64);
        player.stacks[1] = new GenericStack(ITEM, 64);
        player.stacks[2] = new GenericStack(FLUID, 18);
        player.stacks[37] = new GenericStack(ITEM, 40);
        assertTrue(window.interact(window.capture().revision(), true, 0,
            storage -> ResourceHatchBulkTransfer.insertAllSame(storage, ITEM, player)));
        assertNull(player.stacks[0]);
        assertEquals(new GenericStack(ITEM, 28), player.stacks[1]);
        assertEquals(new GenericStack(FLUID, 18), player.stacks[2]);
        assertEquals(new GenericStack(ITEM, 40), player.stacks[37]);
        assertEquals(100, input.getAmount(0));
    }

    @Test
    void doubleTapExtractionFillsPartialStacksAndOffhandFromTheWholeLogicalSide() {
        var input = buffer(40_001, Map.of(FIRST, 1_000L));
        input.insert(0, ITEM, 10, Actionable.MODULATE);
        input.insert(40_000, ITEM, 500, Actionable.MODULATE);
        var window = new ResourceHatchWindow(hatch(input, null), 4, 3);
        var player = new PlayerStacks();
        Arrays.fill(player.stacks, new GenericStack(FLUID, 64));
        player.stacks[0] = null;
        player.stacks[1] = new GenericStack(ITEM, 60);
        player.stacks[2] = null;
        player.stacks[37] = null;
        assertTrue(window.interact(window.capture().revision(), true, 0,
            storage -> ResourceHatchBulkTransfer.extractAllSame(storage, ITEM, player)));
        assertNull(player.stacks[0]);
        assertEquals(new GenericStack(ITEM, 64), player.stacks[1]);
        assertEquals(new GenericStack(ITEM, 64), player.stacks[2]);
        assertEquals(new GenericStack(ITEM, 64), player.stacks[37]);
        assertEquals(new GenericStack(FLUID, 64), player.stacks[3]);
        assertEquals(0, input.getAmount(0));
        assertEquals(378, input.getAmount(40_000));
    }

    @Test
    void anOldOpenOrMoveIdentityCannotEnterAnotherWindowWithTheSameNativeId() {
        var oldWindow = new ResourceHatchWindow(hatch(buffer(40, Map.of(FIRST, 100L)), null), 4, 3);
        var newWindow = new ResourceHatchWindow(hatch(buffer(40, Map.of(FIRST, 100L)), null), 4, 3);
        long oldOpen = oldWindow.capture().revision(), newOpen = newWindow.capture().revision();
        assertTrue(oldWindow.move(oldOpen, 1, 0));
        long oldMove = oldWindow.capture().revision();
        for (long oldIdentity : new long[]{oldOpen, oldMove}) {
            assertFalse(newWindow.move(oldIdentity, 1, 0));
            assertFalse(newWindow.interact(oldIdentity, true, 0, ignored -> {
                throw new AssertionError("Old CLICK/extraId entered new window");
            }));
        }
        assertTrue(newWindow.move(newOpen, 1, 0));
        long newMove = newWindow.capture().revision();
        assertFalse(oldWindow.move(newMove, 2, 0));
        assertFalse(oldWindow.interact(newMove, true, 4, ignored -> {
            throw new AssertionError("Another window's move token was reused");
        }));
    }

    @Test
    void receivedMappingCannotAuthorizeInputUntilItsFrameIsPresented() {
        var input = buffer(40, Map.of(FIRST, 100L, SECOND, 100L));
        input.insert(4, ITEM, 20, Actionable.MODULATE);
        input.insert(8, FLUID, 30, Actionable.MODULATE);
        var window = new ResourceHatchWindow(hatch(input, null), 4, 3);
        var old = window.capture();
        var presentation = new ResourceHatchPresentation();
        assertEquals(old, presentation.receive(old));
        presentation.beginLayout(old.revision());
        presentation.completeFrame();
        long press = presentation.gestureRevision();
        assertTrue(window.move(old.revision(), 1, 0));
        var received = window.capture();
        assertEquals(received, presentation.receive(received));
        // The same native cell still has its old drawn position when the new packet arrives.
        int reusedCell = 4;
        GenericStack[] carried = {null};
        long keyboard = presentation.gestureRevision();
        if (presentation.accepts(received.revision(), keyboard)) {
            int logical = received.input().logicalSlot(reusedCell);
            window.interact(received.revision(), true, logical,
                storage -> carried[0] = HatchSlotActions.click(storage, logical, null, 64, true, false));
        }
        assertNull(carried[0]);
        assertEquals(20, input.getAmount(4));
        assertEquals(received, presentation.receive(old));
        assertFalse(presentation.accepts(received.revision(), presentation.gestureRevision()));
        assertEquals(30, input.getAmount(8));
        presentation.beginLayout(received.revision());
        assertFalse(presentation.accepts(received.revision(), keyboard));
        presentation.completeFrame();
        assertFalse(presentation.accepts(received.revision(), press));
        assertTrue(presentation.accepts(received.revision(), presentation.gestureRevision()));
        assertTrue(window.interact(received.revision(), true, received.input().logicalSlot(reusedCell),
            storage -> carried[0] = HatchSlotActions.click(storage, 8, null, 64, true, false)));
        assertEquals(new GenericStack(FLUID, 30), carried[0]);
        assertEquals(20, input.getAmount(4));
    }

    @Test
    void matchingTransferKeepsAConcurrentReplacementKeyInItsSlot() {
        var input = buffer(1, Map.of(FIRST, 100L, SECOND, 100L));
        var original = new GenericStack(ITEM, 40);
        var replacement = new GenericStack(FLUID, 50);
        input.insert(0, ITEM, 40, Actionable.MODULATE);
        // The writer completes after the caller selected A but before its transfer acquires the lock.
        assertTrue(input.tryReplaceSlot(0, original, replacement));
        long[] extracted = {0};
        ResourceHatchBulkTransfer.withSlotKey(input, 0, ITEM,
            key -> extracted[0] = input.extract(0, key, 64, Actionable.MODULATE));
        assertEquals(0, extracted[0]);
        assertEquals(replacement, input.getStack(0));
    }

    @Test
    void firstQuickMoveBindsItsRecordedKeyAndExtractionUnderTheSameLock() {
        var input = buffer(1, Map.of(FIRST, 100L, SECOND, 100L));
        input.insert(0, ITEM, 40, Actionable.MODULATE);
        AEKey[] recorded = {null};
        try (var writer = Executors.newSingleThreadExecutor()) {
            ResourceHatchBulkTransfer.withSlotKey(input, 0, null, key -> {
                recorded[0] = key;
                boolean entered = assertDoesNotThrow(() -> writer.submit(() -> {
                    if (!input.context().tryOrdinaryLock()) return false;
                    try {
                        input.tryReplaceSlot(0, new GenericStack(ITEM, 40), new GenericStack(FLUID, 50));
                        return true;
                    } finally {
                        input.context().unlock();
                    }
                }).get(5, TimeUnit.SECONDS));
                assertFalse(entered);
                assertEquals(40, input.extract(0, key, 64, Actionable.MODULATE));
            });
        }
        assertEquals(ITEM, recorded[0]);
        assertNull(input.getStack(0));
    }

    @Test
    void doubleTapOnFluidWithAnOrdinaryItemCursorFallsBackToTheUnseenFreeSlot() {
        var input = buffer(40_001, Map.of(FIRST, 64L, SECOND, 1_000L));
        assertEquals(40_000_000, input.insert(FLUID, 40_000_000, Actionable.MODULATE));
        var player = new PlayerStacks();
        player.stacks[0] = new GenericStack(ITEM, 32);
        player.stacks[1] = new GenericStack(ITEM, 16);
        player.stacks[37] = new GenericStack(ITEM, 16);
        var window = new ResourceHatchWindow(hatch(input, null), 4, 3);
        long[] filled = {0};
        assertTrue(window.interact(window.capture().revision(), true, 0,
            storage -> ResourceHatchBulkTransfer.doubleClick(storage, 0, true, 0, player, containerActions(false, storage, filled))));
        assertNull(player.stacks[0]);
        assertNull(player.stacks[1]);
        assertNull(player.stacks[37]);
        assertEquals(new GenericStack(ITEM, 64), input.getStack(40_000));
        assertEquals(new GenericStack(FLUID, 1_000), input.getStack(0));
        assertEquals(0, filled[0]);
    }

    @Test
    void aHeldContainerThatAcceptsTheSelectedResourceKeepsFillPriority() {
        var input = buffer(2, Map.of(FIRST, 64L, SECOND, 1_000L));
        input.insert(0, FLUID, 1_000, Actionable.MODULATE);
        var player = new PlayerStacks();
        player.stacks[0] = new GenericStack(ITEM, 1);
        player.stacks[1] = new GenericStack(ITEM, 16);
        long[] filled = {0};
        ResourceHatchBulkTransfer.doubleClick(input, 0, true, 0, player, containerActions(true, input, filled));
        assertEquals(100, filled[0]);
        assertEquals(new GenericStack(FLUID, 900), input.getStack(0));
        assertNull(input.getStack(1));
        assertEquals(new GenericStack(ITEM, 1), player.stacks[0]);
        assertEquals(new GenericStack(ITEM, 16), player.stacks[1]);
    }

    private static final class PlayerStacks implements ResourceHatchBulkTransfer.PlayerSlots {
        private final GenericStack[] stacks = new GenericStack[38];

        @Override
        public int size() {
            return stacks.length;
        }

        @Override
        public @Nullable GenericStack get(int slot) {
            return stacks[slot];
        }

        @Override
        public void set(int slot, @Nullable GenericStack stack) {
            stacks[slot] = stack;
        }

        @Override
        public long limit(int slot, AEKey key) {
            return 64;
        }
    }
}
