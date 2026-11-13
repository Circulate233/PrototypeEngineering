package com.circulation.prototype_eng.client.preview;

import ae2.client.gui.AEBaseGui;
import ae2.container.slot.AppEngSlot;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.client.gui.GuiStructurePreview;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/**
 * Navigation for the preview's own screens and the native AE sub-screen API.
 */
public final class PreviewScreenTransitions {
    private static @Nullable Session opening;

    private PreviewScreenTransitions() {
    }

    public static Session capture(StructurePreviews service) {
        return new Session(service);
    }

    public static boolean suspending(GuiContainer screen, Container container) {
        return opening != null && opening.parent == screen && opening.container == container;
    }

    record SavedSlot(Slot slot, int x, int y, boolean enabled) {
        SavedSlot(Slot slot) {
            this(slot, slot.xPos, slot.yPos, !(slot instanceof AppEngSlot ae) || ae.isSlotEnabled());
        }

        void restore() {
            slot.xPos = x;
            slot.yPos = y;
            if (slot instanceof AppEngSlot ae) ae.setSlotEnabled(enabled);
        }

        void suspend() {
            slot.xPos = slot.yPos = -9999;
            if (slot instanceof AppEngSlot ae) ae.setSlotEnabled(false);
        }
    }

    public static final class Session {
        private final StructurePreviews service;
        private final EntityPlayer player;
        private final Object world, connection;
        private final Container container;
        private final int window;
        private final @Nullable GuiScreen parent;
        private final ObjectArrayList<SavedSlot> originalSlots;
        private GuiStructurePreview<?> viewer;

        private Session(StructurePreviews service) {
            var mc = Minecraft.getMinecraft();
            this.service = service;
            player = mc.player;
            world = mc.world;
            connection = mc.getConnection();
            container = player.openContainer;
            window = container.windowId;
            parent = mc.currentScreen;
            originalSlots = new ObjectArrayList<>(container.inventorySlots.size());
            for (var slot : container.inventorySlots) originalSlots.add(new SavedSlot(slot));
        }

        public boolean ownerCurrent() {
            var mc = Minecraft.getMinecraft();
            return mc.player == player && mc.world == world && mc.getConnection() == connection
                && player.openContainer == container && container.windowId == window
                && player.isEntityAlive() && !service.closed();
        }

        public boolean viewerCurrent(GuiStructurePreview<?> screen) {
            return viewer == screen && Minecraft.getMinecraft().currentScreen == screen && ownerCurrent();
        }

        public boolean open(GuiStructurePreview<?> screen) {
            var mc = Minecraft.getMinecraft();
            if (viewer != null || !ownerCurrent() || mc.currentScreen != parent) return false;
            viewer = screen;
            var previous = opening;
            opening = this;
            try {
                if (parent instanceof AEBaseGui<?> ae && screen.getContainer() == container) ae.switchToScreen(screen);
                else mc.displayGuiScreen(screen);
            } finally {
                opening = previous;
                if (mc.currentScreen != screen) {
                    screen.releasePreview();
                    if (mc.currentScreen == parent && ownerCurrent()) restoreSlots();
                }
            }
            return viewerCurrent(screen);
        }

        private void restoreSlots() {
            for (var saved : originalSlots) if (container.inventorySlots.contains(saved.slot)) saved.restore();
        }

        public void suspendNativeSlots(GuiStructurePreview<?> screen) {
            if (screen.getContainer() != container || !viewerCurrent(screen)) return;
            for (var saved : originalSlots) if (container.inventorySlots.contains(saved.slot)) saved.suspend();
        }

        public void back() {
            var mc = Minecraft.getMinecraft();
            if (viewer == null || mc.currentScreen != viewer) return;
            if (!ownerCurrent() || parent == null) {
                mc.displayGuiScreen(null);
            } else if (parent instanceof AEBaseGui<?> ae && viewer.getContainer() == container) {
                viewer.switchToScreen(ae);
            } else mc.displayGuiScreen(parent);
            if (mc.currentScreen == parent && ownerCurrent()) {
                restoreSlots();
                if (parent instanceof AEBaseGui<?> ae) ae.returnFromSubScreen(viewer);
            }
        }
    }
}
