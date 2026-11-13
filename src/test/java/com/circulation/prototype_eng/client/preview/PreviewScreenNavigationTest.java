package com.circulation.prototype_eng.client.preview;

import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PreviewScreenNavigationTest {
    @Test
    void backMouseAndEscapeDuringDragWaitForTheCapturedRelease() {
        for (boolean escape : new boolean[]{false, true}) {
            var held = new AtomicBoolean(true);
            var returns = new AtomicInteger();
            var back = new PreviewBackRequest(() -> !held.get(), returns::incrementAndGet);
            if (escape) back.keyPress();
            else back.mousePress();
            back.finishReleased();
            assertEquals(0, returns.get());
            held.set(false);
            back.finishReleased();
            assertEquals(1, returns.get());
            back.finishReleased();
            assertEquals(1, returns.get());
        }
    }

    @Test
    void commandQueueWaitsUntilChatClosedAndActualEndTick() {
        var queue = PreviewClientOpenQueue.INSTANCE;
        var chat = new Object();
        var viewer = new Object();
        var screen = new AtomicReference<>(chat);
        queue.enqueue(() -> {
            if (screen.get() == null) screen.set(viewer);
        });
        assertSame(chat, screen.get());
        screen.set(null);
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        assertNull(screen.get());
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        assertSame(viewer, screen.get());
    }

    @Test
    void callbackEnqueuedDuringOpenWaitsForFollowingTick() {
        var queue = PreviewClientOpenQueue.INSTANCE;
        var calls = new AtomicInteger();
        queue.enqueue(() -> {
            calls.incrementAndGet();
            queue.enqueue(calls::incrementAndGet);
        });
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        assertEquals(1, calls.get());
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        assertEquals(2, calls.get());
    }
}
