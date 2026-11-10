package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.util.function.BiPredicate;

/** HEI's wrapper API has no drag/key callbacks: forward only inside the actually drawn preview. */
public final class PreviewEmbeddedInput {
    public static final PreviewEmbeddedInput INSTANCE = new PreviewEmbeddedInput();
    private final ObjectArrayList<Active> active = new ObjectArrayList<>();
    private final Drag dragging = new Drag();
    private PreviewEmbeddedInput() {
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
                && entry.panel.renderItemTooltip(event.getMouseX(), event.getMouseY(), event.getMouseX() - entry.x, event.getMouseY() - entry.y)) return;
        }
    }

    private boolean fresh(Active entry) {
        return entry.panel.inputCurrent() && entry.host == Minecraft.getMinecraft().currentScreen && System.currentTimeMillis() - entry.touched <= 250;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (var entry : new ObjectArrayList<>(active))
            if (!entry.panel.ownerCurrent() || System.currentTimeMillis() - entry.touched > 250) entry.panel.close();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void mouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        int eventButton = Mouse.getEventButton();
        boolean ownedRelease = dragging.consumeRelease(eventButton, Mouse.getEventButtonState());
        if (PreviewScreenTransitions.blocked(event.getGui())) {
            if (ownedRelease) dragging.clear();
            event.setCanceled(true);
            return;
        }
        var mc = Minecraft.getMinecraft();
        var scaled = new ScaledResolution(mc);
        int x = Mouse.getEventX() * scaled.getScaledWidth() / mc.displayWidth;
        int y = scaled.getScaledHeight() - Mouse.getEventY() * scaled.getScaledHeight() / mc.displayHeight - 1;
        if (ownedRelease) {
            if (dragging.fresh(event.getGui(), System.currentTimeMillis()) && ((StructurePreviewPanel) dragging.panel).inputCurrent()) {
                var panel = (StructurePreviewPanel) dragging.panel;
                int capturedButton = dragging.button;
                dragging.release(x, y, (localX, localY) -> panel.release(localX, localY, capturedButton));
            } else dragging.clear();
            event.setCanceled(true);
            return;
        }
        if (dragging.fresh(event.getGui(), System.currentTimeMillis()) && ((StructurePreviewPanel) dragging.panel).inputCurrent()) {
            var panel = (StructurePreviewPanel) dragging.panel;
            int capturedButton = dragging.button;
            if (eventButton == -1 && Mouse.isButtonDown(capturedButton) && dragging.move(x, y, panel::drag)) {
                event.setCanceled(true);
                return;
            }
        }
        for (int index = active.size() - 1; index >= 0; index--) {
            var entry = active.get(index);
            if (!fresh(entry) || entry.host != event.getGui()) continue;
            int localX = x - entry.x, localY = y - entry.y;
            int wheel = Mouse.getEventDWheel();
            if (wheel != 0 && entry.panel.wheel(localX, localY, wheel)) {
                event.setCanceled(true);
                return;
            }
            if (eventButton >= 0 && Mouse.getEventButtonState() && entry.panel.press(localX, localY, eventButton)) {
                // Preserve legitimate captured drags beyond the viewport; unrelated outside clicks still pass through.
                if (entry.panel.scene().viewport().contains(x, y))
                    dragging.capture(entry.host, entry.panel, entry.x, entry.y, eventButton, entry.touched);
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void key(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (PreviewScreenTransitions.blocked(event.getGui())) event.setCanceled(true);
    }

    private record Active(GuiScreen host, StructurePreviewPanel panel, int x, int y, long touched) {
    }

    /** Pure captured-input state; native panel freshness is checked by the caller before delivery. */
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

        boolean move(int screenX, int screenY, BiPredicate<Integer, Integer> deliver) {
            return deliver.test(screenX - x, screenY - y);
        }

        void release(int screenX, int screenY, BiPredicate<Integer, Integer> deliver) {
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
    }
}
