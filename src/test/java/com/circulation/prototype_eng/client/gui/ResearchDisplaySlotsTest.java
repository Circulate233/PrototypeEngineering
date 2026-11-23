package com.circulation.prototype_eng.client.gui;

import ae2.api.stacks.GenericStack;
import ae2.container.slot.AppEngSlot;
import ae2.container.slot.InaccessibleSlot;
import com.circulation.prototype_eng.testsupport.TestResources;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("DataFlowIssue") // Assertions establish a visible slot before reading its ingredient.
class ResearchDisplaySlotsTest {
    private static final TestResources.KeyType TYPE = new TestResources.KeyType("research_gui_material");
    private static final TestResources.Key A = new TestResources.Key(TYPE, "a"), B = new TestResources.Key(TYPE, "b");

    @Test
    void scaledAndClippedSlotExposesTheVisibleIngredientWithItsFullLongAmount() {
        var slots = new ResearchDisplaySlots();
        slots.beginFrame(0.5, 10, 20);
        slots.clip(0, 10, 100, 20);
        slots.add(A, Long.MAX_VALUE, 20, 0, 36);
        var nativeSlot = assertInstanceOf(InaccessibleSlot.class, slots.find(25, 25));
        assertTrue(nativeSlot.hasGenericDisplayStack());
        assertEquals(A, nativeSlot.getGenericDisplayKey());
        assertEquals(Long.MAX_VALUE, nativeSlot.getGenericDisplayAmount());
        assertFalse(nativeSlot.isItemValid(ItemStack.EMPTY));
        assertFalse(nativeSlot.canTakeStack(null));
        var hovered = Objects.requireNonNull(slots.stackAt(25, 25));
        assertEquals(new GenericStack(A, Long.MAX_VALUE), hovered.stack());
        assertEquals(new Rectangle(20, 25, 18, 10), hovered.bounds());
        assertNull(slots.find(25, 24));
        assertNull(slots.find(25, 35));
        assertNull(slots.find(38, 25));
        assertEquals(hovered.stack(), slots.stackAt(25, 25).stack());
    }

    @Test
    void nextFrameRetiresOldSlotsAndReusesThemForTheNewVisibleMaterials() {
        var slots = new ResearchDisplaySlots();
        slots.beginFrame(1, 0, 0);
        slots.clip(0, 0, 100, 100);
        slots.add(A, 12, 10, 10, 16);
        var previous = assertInstanceOf(AppEngSlot.class, slots.find(11, 11));
        var captured = Objects.requireNonNull(slots.stackAt(11, 11));
        slots.beginFrame(1, 0, 0);
        assertNull(slots.find(11, 11));
        assertFalse(previous.isEnabled());
        assertFalse(previous.hasGenericDisplayStack());
        assertTrue(previous.getStack().isEmpty());
        slots.clip(0, 0, 100, 100);
        slots.add(B, 0, 40, 40, 16);
        assertSame(previous, slots.find(41, 41));
        assertTrue(previous.hasGenericDisplayStack());
        assertEquals(B, previous.getGenericDisplayKey());
        assertEquals(0, previous.getGenericDisplayAmount());
        assertEquals(new GenericStack(B, 0), slots.stackAt(41, 41).stack());
        assertEquals(new GenericStack(A, 12), captured.stack());
        assertNull(slots.find(11, 11));
    }

    @Test
    void laterDrawnSlotsWinAndScrollingOnlyExposesTheClippedPart() {
        var slots = new ResearchDisplaySlots();
        slots.beginFrame(1, 80, 40);
        slots.clip(0, 0, 60, 60);
        slots.add(A, 5, 20, 20, 36);
        slots.clip(25, 25, 20, 20);
        slots.add(B, 7, 20, 10, 36);
        assertEquals(A, slots.stackAt(102, 62).stack().what());
        assertEquals(B, slots.stackAt(107, 67).stack().what());
        assertEquals(new Rectangle(105, 65, 20, 20), slots.stackAt(107, 67).bounds());
        assertEquals(A, slots.stackAt(126, 67).stack().what());
        slots.beginFrame(1, 80, 40);
        slots.clip(25, 25, 20, 20);
        slots.add(B, 7, 20, -30, 36);
        assertNull(slots.find(107, 67));
        assertNull(slots.stackAt(107, 67));
    }

}
