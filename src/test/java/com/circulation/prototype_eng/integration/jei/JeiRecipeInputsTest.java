package com.circulation.prototype_eng.integration.jei;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the native key boundary and buffered-wheel ownership without an LWJGL device or game bootstrap. */
class JeiRecipeInputsTest {
    @Test
    void actualNativeKeyPreRevokesOldWrapperBeforeAnotherBufferedWheelOnSameScreen() {
        var screen = new GuiScreen() {};
        JeiRecipeInputs.keyboard(new GuiScreenEvent.KeyboardInputEvent.Pre(screen));
        int[] oldPage = {0};
        JeiRecipeInputs.observe(screen, (x, y, delta) -> {
            assertEquals(1, x); assertEquals(1, y); assertEquals(-120, delta);
            oldPage[0]++; return true;
        }, 20, 30, 174, 160);
        assertTrue(JeiRecipeInputs.scroll(screen, 21, 31, -120));
        assertEquals(1, oldPage[0]);
        // Native JEI can updateLayout in this same screen while processing this key.
        JeiRecipeInputs.keyboard(new GuiScreenEvent.KeyboardInputEvent.Pre(screen));
        assertFalse(JeiRecipeInputs.scroll(screen, 21, 31, -120));
        assertEquals(1, oldPage[0]);
        int[] currentPage = {0};
        JeiRecipeInputs.observe(screen, (x, y, delta) -> {
            assertEquals(1, x); assertEquals(1, y); assertEquals(-120, delta);
            currentPage[0]++; return true;
        }, 20, 30, 174, 160);
        assertTrue(JeiRecipeInputs.scroll(screen, 21, 31, -120));
        assertEquals(1, currentPage[0]);
        assertEquals(1, oldPage[0]);
        JeiRecipeInputs.keyboard(new GuiScreenEvent.KeyboardInputEvent.Pre(screen));
    }

    @Test
    void wheelPassedToNativeLayoutCannotBeConsumedByOldObservationOnNextBufferedEvent() {
        var screen = new GuiScreen() {};
        JeiRecipeInputs.keyboard(new GuiScreenEvent.KeyboardInputEvent.Pre(screen));
        int[] calls = {0};
        JeiRecipeInputs.observe(screen, (x, y, delta) -> {
            assertEquals(1, x); assertEquals(1, y); assertEquals(-120, delta);
            calls[0]++; return false;
        }, 20, 30, 174, 160);
        assertFalse(JeiRecipeInputs.scroll(screen, 21, 31, -120));
        assertFalse(JeiRecipeInputs.scroll(screen, 21, 31, -120));
        assertEquals(1, calls[0]);
        // A wheel outside the old wrapper may itself change native page/category.
        JeiRecipeInputs.observe(screen, (_, _, _) -> { throw new AssertionError("Outside wheel must not reach old wrapper"); },
            20, 30, 174, 160);
        assertFalse(JeiRecipeInputs.scroll(screen, 200, 31, -120));
        assertFalse(JeiRecipeInputs.scroll(screen, 21, 31, -120));
    }
}
