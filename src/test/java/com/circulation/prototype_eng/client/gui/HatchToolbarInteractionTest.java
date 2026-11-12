package com.circulation.prototype_eng.client.gui;

import ae2.api.util.AEColor;
import ae2.client.Point;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchToolbarInteractionTest {
    @Test
    void automaticIoChangesOnlyOnMatchingReleaseInsideAndNeverLeavesRightPressLatched() {
        int[] toggles = {0};
        var button = new HatchToggleButton(true, () -> false, true, () -> toggles[0]++, new Point(1, 12));
        var inside = new Point(8, 20);
        assertTrue(button.onMouseDown(inside, 1));
        assertEquals(0, toggles[0]);
        assertTrue(button.onMouseUp(inside, 1));
        assertEquals(1, toggles[0]);
        assertFalse(button.onMouseUp(inside, 1));
        assertTrue(button.onMouseDown(inside, 0));
        assertTrue(button.onMouseUp(new Point(30, 20), 0));
        assertEquals(1, toggles[0]);
        assertTrue(button.onMouseDown(inside, 1));
        assertTrue(button.onMouseUp(inside, 0));
        assertFalse(button.onMouseUp(inside, 1));
        assertEquals(1, toggles[0]);
    }

    @Test
    void disabledAutomaticIoDoesNotCommitOnEitherMouseButton() {
        int[] toggles = {0};
        var button = new HatchToggleButton(false, () -> false, false, () -> toggles[0]++, new Point(1, 12));
        var inside = new Point(8, 20);
        for (int mouseButton = 0; mouseButton <= 1; mouseButton++) {
            assertTrue(button.onMouseDown(inside, mouseButton));
            assertFalse(button.onMouseUp(inside, mouseButton));
        }
        assertEquals(0, toggles[0]);
    }

    @Test
    void colorPaletteOpensAndChoosesOnReleaseAndCancelsDraggedSelection() {
        var choices = new ObjectArrayList<AEColor>();
        var colors = new ResourceColorSelector(() -> AEColor.TRANSPARENT, choices::add);
        var button = new Point(8, 20);
        assertTrue(colors.onMouseDown(button, 0));
        assertFalse(colors.expanded());
        assertTrue(colors.onMouseUp(button, 0));
        assertTrue(colors.expanded());
        var red = new Point(6, 18);
        assertTrue(colors.onMouseDown(red, 0));
        assertTrue(choices.isEmpty());
        assertTrue(colors.onMouseUp(red, 0));
        assertEquals(List.of(AEColor.RED), choices);
        assertFalse(colors.expanded());
        colors.onMouseDown(button, 1);
        colors.onMouseUp(button, 1);
        assertTrue(colors.expanded());
        colors.onMouseDown(red, 0);
        colors.onMouseUp(new Point(19, 18), 0);
        assertEquals(1, choices.size());
        assertFalse(colors.expanded());
        colors.onMouseDown(button, 0);
        colors.onMouseUp(button, 0);
        colors.onMouseDown(red, 1);
        assertTrue(colors.onKeyTyped((char) 0, 1));
        assertFalse(colors.onMouseUp(red, 1));
        assertFalse(colors.expanded());
    }
}
