package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;


/**
 * HEI's wrapper API has no drag/key callbacks: forward only inside the actually drawn preview.
 */
public final class PreviewEmbeddedInput {
    public static final PreviewEmbeddedInput INSTANCE = new PreviewEmbeddedInput();
    private final ObjectArrayList<Active> active = new ObjectArrayList<>();
    private final Drag dragging = new Drag();

    private PreviewEmbeddedInput() {
    }

    private static boolean expired(Active entry) {
        return !entry.panel.ownerCurrent() || System.currentTimeMillis() - entry.touched > 250;
    }

    void mark(GuiScreen host, StructurePreviewPanel panel) {
        if (host == null) return;
        active.removeIf(entry -> entry.panel == panel);
        var rect = panel.scene().viewport();
        var next = new Active(host, panel, (int) Math.round(rect.x() - StructurePreviewPanel.SCENE_X), (int) Math.round(rect.y() - StructurePreviewPanel.SCENE_Y), System.currentTimeMillis());
        active.add(next);
        dragging.mark(host, panel, next.x, next.y, next.touched);
    }

    void remove(StructurePreviewPanel panel) {
        active.removeIf(entry -> entry.panel == panel);
        if (dragging.panel == panel) dragging.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tooltip(GuiScreenEvent.DrawScreenEvent.Post event) {
        for (int index = active.size() - 1; index >= 0; index--) {
            var entry = active.get(index);
            if (entry.host == event.getGui() && fresh(entry)
                && entry.panel.renderItemTooltip(event.getMouseX(), event.getMouseY(), event.getMouseX() - entry.x, event.getMouseY() - entry.y))
                return;
        }
    }

    private boolean fresh(Active entry) {
        return entry.panel.inputCurrent() && entry.host == Minecraft.getMinecraft().currentScreen && System.currentTimeMillis() - entry.touched <= 250;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (int index = 0; index < active.size(); index++) {
            var entry = active.get(index);
            if (!expired(entry)) continue;
            // Reads above invoke no cleanup. Freeze only the unvisited suffix before close can
            // remove/reopen panels; the ordinary all-current tick allocates no snapshot.
            var remaining = new ObjectArrayList<>(active.subList(index, active.size()));
            remaining.getFirst().panel.close();
            for (int cursor = 1; cursor < remaining.size(); cursor++) {
                var next = remaining.get(cursor);
                if (expired(next)) next.panel.close();
            }
            return;
        }
    }

    /**
     * Called at HEI's actual input entry, which runs even for already-cancelled Forge events.
     */
    public boolean mouse(GuiScreen screen, int x, int y) {
        int eventButton = Mouse.getEventButton();
        boolean ownedRelease = dragging.consumeRelease(eventButton, Mouse.getEventButtonState());
        if (ownedRelease) {
            if (dragging.fresh(screen, System.currentTimeMillis()) && ((StructurePreviewPanel) dragging.panel).inputCurrent()) {
                var panel = (StructurePreviewPanel) dragging.panel;
                int capturedButton = dragging.button;
                dragging.release(x, y, (localX, localY) -> panel.release(localX, localY, capturedButton));
            } else dragging.clear();
            return true;
        }
        if (dragging.fresh(screen, System.currentTimeMillis()) && ((StructurePreviewPanel) dragging.panel).inputCurrent()) {
            var panel = (StructurePreviewPanel) dragging.panel;
            int capturedButton = dragging.button;
            if (eventButton == -1 && Mouse.isButtonDown(capturedButton) && dragging.move(x, y, panel::drag)) {
                return true;
            }
        }
        for (int index = active.size() - 1; index >= 0; index--) {
            var entry = active.get(index);
            if (!fresh(entry) || entry.host != screen) continue;
            int localX = x - entry.x, localY = y - entry.y;
            int wheel = Mouse.getEventDWheel();
            if (wheel != 0 && entry.panel.wheel(localX, localY, wheel)) {
                return true;
            }
            if (eventButton >= 0 && Mouse.getEventButtonState() && entry.panel.press(localX, localY, eventButton)) {
                // Preserve legitimate captured drags beyond the viewport; unrelated outside clicks still pass through.
                if (entry.panel.scene().viewport().contains(x, y))
                    dragging.capture(entry.host, entry.panel, entry.x, entry.y, eventButton, entry.touched);
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void key(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE)
            for (int index1 = 0; index1 < active.size(); index1++) {
                var entry = active.get(index1);
                if (entry.host == event.getGui() && fresh(entry) && entry.panel.dismissMaterials()) {
                    event.setCanceled(true);
                    return;
                }
            }
    }

    private record Active(GuiScreen host, StructurePreviewPanel panel, int x, int y, long touched) {
    }

    /**
     * Pure captured-input state; native panel freshness is checked by the caller before delivery.
     */
    static final class Drag {
        private Object host, panel;
        private int x, y, button = -1;
        private int ownedRelease = -1;
        private long touched;

        void mark(Object nextHost, Object nextPanel, int nextX, int nextY, long now) {
            if (panel == nextPanel && host == nextHost) {
                x = nextX;
                y = nextY;
                touched = now;
            }
        }

        void capture(Object nextHost, Object nextPanel, int nextX, int nextY, int pressed, long now) {
            host = nextHost;
            panel = nextPanel;
            x = nextX;
            y = nextY;
            button = ownedRelease = pressed;
            touched = now;
        }

        boolean fresh(Object currentHost, long now) {
            return panel != null && host == currentHost && now - touched <= 250;
        }

        boolean move(int screenX, int screenY, PointerAction deliver) {
            return deliver.test(screenX - x, screenY - y);
        }

        void release(int screenX, int screenY, PointerAction deliver) {
            try {
                deliver.test(screenX - x, screenY - y);
            } finally {
                clear();
            }
        }

        void clear() {
            host = panel = null;
            button = -1;
        }

        boolean consumeRelease(int released, boolean down) {
            if (released < 0 || released != ownedRelease) return false;
            ownedRelease = -1;
            if (down) {
                clear();
                return false;
            } // Native display may already have drained the previous release.
            return true;
        }

        @FunctionalInterface
        interface PointerAction {
            boolean test(int x, int y);
        }
    }
}
