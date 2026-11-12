package com.circulation.prototype_eng.client.gui;

import ae2.api.util.AEColor;
import ae2.client.Point;
import ae2.client.gui.WidgetContainer;
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
        for (var style : ResourceColorSelector.Style.VALUES) checkPaletteInteraction(style);
    }

    @Test
    void nativeWidgetDispatchChoosesEveryIsolationGroupAndConsumesOutsideClicks() {
        var palette = new AEColor[]{AEColor.RED, AEColor.BROWN, AEColor.ORANGE, AEColor.YELLOW,
            AEColor.LIME, AEColor.GREEN, AEColor.CYAN, AEColor.LIGHT_BLUE, AEColor.BLUE, AEColor.PURPLE,
            AEColor.MAGENTA, AEColor.PINK, AEColor.WHITE, AEColor.LIGHT_GRAY, AEColor.GRAY, AEColor.BLACK};
        for (var style : ResourceColorSelector.Style.VALUES) {
            var choices = new ObjectArrayList<AEColor>();
            var colors = new ResourceColorSelector(() -> AEColor.TRANSPARENT, choices::add, style);
            colors.setPosition(new Point(1, 13));
            var widgets = new WidgetContainer();
            widgets.add("inputScroll", new HatchScrollbar(false, true));
            widgets.add("outputScroll", new HatchScrollbar(false, true));
            widgets.add("resourceColor", colors);
            var button = new Point(6, 18);
            for (int cell = 0; cell < palette.length; cell++) {
                assertTrue(widgets.onMouseDown(button, 0));
                assertTrue(widgets.onMouseUp(button, 0));
                assertTrue(colors.expanded());
                var swatch = new Point(6 + cell % 4 * 13, 18 + cell / 4 * 13);
                assertTrue(widgets.blocksMouseInteraction(swatch));
                assertTrue(widgets.onMouseDown(swatch, 0));
                assertTrue(widgets.onMouseUp(swatch, 0));
                assertFalse(colors.expanded());
                assertEquals(palette[cell], choices.getLast());
                assertFalse(widgets.onMouseUp(swatch, 0));
            }
            widgets.onMouseDown(button, 0);
            widgets.onMouseUp(button, 0);
            var uncolored = new Point(6, 75);
            assertTrue(widgets.onMouseDown(uncolored, 0));
            assertTrue(widgets.onMouseUp(uncolored, 0));
            assertEquals(AEColor.TRANSPARENT, choices.getLast());
            widgets.onMouseDown(button, 0);
            widgets.onMouseUp(button, 0);
            var outside = new Point(200, 200);
            assertTrue(widgets.onMouseDown(outside, 0));
            assertTrue(widgets.onMouseUp(outside, 0));
            assertFalse(colors.expanded());
            assertEquals(17, choices.size());
        }
    }

    private static void checkPaletteInteraction(ResourceColorSelector.Style style) {
        var choices = new ObjectArrayList<AEColor>();
        var colors = new ResourceColorSelector(() -> AEColor.TRANSPARENT, choices::add, style);
        colors.setPosition(new Point(4, 13));
        var button = new Point(8, 20);
        assertTrue(colors.onMouseDown(button, 0));
        assertFalse(colors.expanded());
        assertTrue(colors.onMouseUp(button, 0));
        assertTrue(colors.expanded());
        var red = new Point(9, 18);
        assertTrue(colors.onMouseDown(red, 0));
        assertTrue(choices.isEmpty());
        assertTrue(colors.onMouseUp(red, 0));
        assertEquals(List.of(AEColor.RED), choices);
        assertFalse(colors.expanded());
        colors.onMouseDown(button, 1);
        colors.onMouseUp(button, 1);
        assertTrue(colors.expanded());
        colors.onMouseDown(red, 0);
        colors.onMouseUp(new Point(22, 18), 0);
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
