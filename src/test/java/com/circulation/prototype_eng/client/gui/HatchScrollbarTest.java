package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import com.circulation.prototype_eng.container.ResourceHatchWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchScrollbarTest {
    @Test
    void meMarkedInputUsesContinuousPixelsAndItsMeasuredTrackWithoutDroppingTheLastPartialRow() {
        var input = new HatchScrollbar(false, true);
        input.configure(26, 24, 8, 68, 36, 3, 175, 25, 67);
        assertTrue(input.onMouseDown(new Point(180, 90), 0));
        assertEquals(40, input.scroll());
        assertTrue(input.onMouseDrag(new Point(180, 25), 0));
        assertEquals(0, input.scroll());
        input.onMouseUp(new Point(180, 25), 0);
        assertEquals(HatchScrollbar.ThumbState.HOVER, input.thumbState(new Point(180, 26)));
    }

    @Test
    void meOutputSharesTheSameTrackDraggingButItsOwnPixelRange() {
        var output = new HatchScrollbar(false, true);
        output.configure(26, 98, 8, 68, 18, 5, 175, 98, 67);
        assertTrue(output.onMouseDown(new Point(180, 163), 0));
        assertEquals(22, output.scroll());
        output.onMouseUp(new Point(180, 163), 0);
        output.configure(26, 98, 8, 68, 18, 3, 175, 98, 67);
        assertFalse(output.isScrollable());
        assertEquals(0, output.scroll());
        assertEquals(HatchScrollbar.ThumbState.DISABLED, output.thumbState(new Point(180, 99)));
    }

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
