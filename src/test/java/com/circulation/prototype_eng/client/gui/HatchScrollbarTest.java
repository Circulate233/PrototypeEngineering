package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import com.circulation.prototype_eng.container.ResourceHatchWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchScrollbarTest {
    @Test
    void draggingCanReachTheLastIntSlotWithoutPixelOverflow() {
        var bar = new HatchScrollbar(true, true);
        int rows = ResourceHatchWindow.rows(Integer.MAX_VALUE, 4);
        bar.configure(26, 24, 4, 3, rows);
        assertTrue(bar.onMouseDown(new Point(103, 77), 0));
        assertEquals((rows - 3L) * 18, bar.scroll());
        assertEquals(Integer.MAX_VALUE - 1L, bar.scroll() / 18 * 4 + 10);
        assertTrue(bar.onMouseDrag(new Point(103, 24), 0));
        assertEquals(0, bar.scroll());
    }

    @Test
    void presentSideKeepsItsDisabledThumbWhenEverythingFits() {
        for (boolean thin : new boolean[] { false, true }) {
            var bar = new HatchScrollbar(thin, true);
            bar.configure(26, 24, 4, 3, 3);
            var mouse = new Point(bar.getBounds().x + 1, 25);
            assertTrue(bar.isVisible());
            assertFalse(bar.isScrollable());
            assertEquals(HatchScrollbar.ThumbState.DISABLED, bar.thumbState(mouse));
            assertFalse(bar.onMouseWheel(mouse, -1));
            assertFalse(bar.onMouseDown(mouse, 0));
            assertFalse(bar.onMouseDrag(new Point(mouse.x(), 75), 0));
            bar.frame(new Point(mouse.x(), 75));
            assertEquals(0, bar.scroll());
            bar.configure(26, 24, 4, 3, 0);
            assertTrue(bar.isVisible());
            assertEquals(HatchScrollbar.ThumbState.DISABLED, bar.thumbState(mouse));
        }
    }

    @Test
    void absentSideDoesNotRenderOrRespondEvenIfRowsAreProvided() {
        var bar = new HatchScrollbar(false, false);
        bar.configure(26, 24, 4, 3, 10);
        var mouse = new Point(bar.getBounds().x + 1, 25);
        assertFalse(bar.isVisible());
        assertFalse(bar.isScrollable());
        assertFalse(bar.onMouseWheel(mouse, -1));
        assertFalse(bar.onMouseDown(mouse, 0));
        assertFalse(bar.onMouseDrag(new Point(mouse.x(), 75), 0));
        assertEquals(0, bar.scroll());
    }

    @Test
    void mixedSidesKeepIndependentRangesAndDisabledHoverDoesNotHighlight() {
        var input = new HatchScrollbar(true, true);
        var output = new HatchScrollbar(true, true);
        input.configure(26, 24, 4, 3, 8);
        output.configure(114, 24, 4, 3, 2);
        var inputMouse = new Point(input.getBounds().x + 1, 25);
        var outputMouse = new Point(output.getBounds().x + 1, 25);
        assertEquals(HatchScrollbar.ThumbState.HOVER, input.thumbState(inputMouse));
        assertEquals(HatchScrollbar.ThumbState.DISABLED, output.thumbState(outputMouse));
        assertTrue(input.onMouseDown(inputMouse, 0));
        assertEquals(HatchScrollbar.ThumbState.PRESSED, input.thumbState(inputMouse));
        assertTrue(input.onMouseDrag(new Point(inputMouse.x(), 77), 0));
        assertEquals(90, input.scroll());
        assertEquals(0, output.scroll());
        assertFalse(output.onMouseWheel(outputMouse, -1));
        input.configure(26, 24, 4, 3, 3);
        assertTrue(input.isVisible());
        assertFalse(input.onMouseDrag(inputMouse, 0));
        assertEquals(HatchScrollbar.ThumbState.DISABLED, input.thumbState(inputMouse));
        assertEquals(0, input.scroll());
    }
}
