package com.circulation.prototype_eng.client.preview;

import ae2.client.gui.AEBaseGui;
import ae2.container.slot.AppEngSlot;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.client.gui.StructurePreviewGui;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.jetbrains.annotations.Nullable;

/** Scoped native GUI handoff. Actual GuiOpenEvent and currentScreen are never forged or bypassed. */
public final class PreviewScreenTransitions {
    private static @Nullable Frame active;
    private static @Nullable PreviewScreenGuard.Retired retired;

    private PreviewScreenTransitions() {
    }

    public static Session capture(StructurePreviews service) {
        return new Session(service);
    }

    private static PreviewScreenGuard.State state(StructurePreviews service, boolean includeCatalog) {
        var mc = Minecraft.getMinecraft();
        var player = mc.player;
        var container = player == null ? null : player.openContainer;
        return new PreviewScreenGuard.State(mc.currentScreen, player, mc.world, mc.getConnection(), container, container == null ? -1 : container.windowId,
            !includeCatalog || service.closed() ? null : service.catalogView(), player != null && player.isEntityAlive() && player.getHealth() > 0);
    }

    /** Called by the exact Minecraft.displayGuiScreen EventBus.post redirect, including nested calls. */
    public static boolean post(EventBus bus, Event event) {
        clearInactiveRetirement();
        var frame = active;
        if (!(event instanceof GuiOpenEvent opening) || frame == null) return bus.post(event);
        boolean first = !frame.postSeen;
        frame.postSeen = true;
        if (first) frame.opening = opening;
        if (first && frame.target == null) frame.guard.normalizeNullTarget(opening.getGui());
        boolean ours = first && frame.guard.requested(opening.getGui()) && frame.guard.current();
        if (!ours) frame.guard.revoke();
        boolean canceled = bus.post(event);
        boolean result = ours ? frame.guard.afterPost(canceled, opening.getGui()) : canceled;
        if (ours && frame.source != null && frame.guard.sourceRetired()) retire(frame, frame.source);
        return result;
    }

    /** Exact Event local identifies this display call after old.onGuiClosed callbacks returned. */
    public static boolean beforeAssign(GuiScreen target, GuiOpenEvent event) {
        var frame = active;
        if (frame == null || frame.opening != event || !frame.guard.displayed(target) || frame.guard.current())
            return false;
        // old.onGuiClosed already ran. If it stayed current, its disposed GUI must be fenced;
        // if it displayed a newer screen, cancel the remainder without touching that screen/focus.
        if (frame.source != null && Minecraft.getMinecraft().currentScreen == frame.source)
            retire(frame, frame.source, true);
        return true;
    }

    /** Parent overrides may change ownership before invoking GuiContainer.initGui's real field-store. */
    public static boolean binding(GuiContainer screen, Container container) {
        var frame = active;
        if (frame == null || frame.target != screen || frame.session.container != container || !frame.guard.displayed(screen))
            return true;
        if (frame.guard.atDisplayed()) return true;
        if (Minecraft.getMinecraft().currentScreen == screen) retire(frame, screen);
        return false;
    }

    /** InitPre is a separate callback boundary, including initialization of the captured parent on return. */
    public static boolean postInit(EventBus bus, Event event) {
        clearInactiveRetirement();
        if (event instanceof GuiScreenEvent.InitGuiEvent.Pre pre && retired != null && retired.blocks(pre.getGui())) {
            // A canceled local cleanup may leave this exact revoked target current. Resize must
            // still post the real Pre event, but may never initialize it and rebind its old C.
            bus.post(event);
            return true;
        }
        var frame = active;
        if (!(event instanceof GuiScreenEvent.InitGuiEvent.Pre pre) || frame == null || frame.target == null || !frame.guard.requested(pre.getGui()) || !frame.guard.displayed(pre.getGui()))
            return bus.post(event);
        boolean canceled = bus.post(event);
        boolean result = frame.guard.afterInitPre(canceled);
        if (!frame.guard.atDisplayed() && Minecraft.getMinecraft().currentScreen == pre.getGui()) {
            retire(frame, pre.getGui());
        }
        return result;
    }

    private static void retire(Frame frame, GuiScreen screen) {
        retire(frame, screen, false);
    }

    private static void retire(Frame frame, GuiScreen screen, boolean guiClosed) {
        var invalid = new PreviewScreenGuard.Retired(screen, frame.session.container, guiClosed || retired != null && retired.alreadyClosed(screen));
        retired = invalid;
        // Input/update/resize are fenced immediately; local close waits for native post/init
        // to unwind. A canceled cleanup retains the exact screen/C fence.
        PreviewClientOpenQueue.INSTANCE.enqueue(() -> {
            if (retired == invalid && invalid.blocks(Minecraft.getMinecraft().currentScreen))
                frame.session.closeLocal(screen);
        });
    }

    private static void clearInactiveRetirement() {
        if (retired != null && !retired.blocks(Minecraft.getMinecraft().currentScreen)) retired = null;
    }

    /** Only a target revoked in the second native post is blocked, until it actually leaves the screen. */
    public static boolean blocked(GuiScreen screen) {
        clearInactiveRetirement();
        return retired != null && retired.blocks(screen);
    }

    public static boolean alreadyClosed(GuiScreen screen) {
        return retired != null && retired.alreadyClosed(screen) || active != null && active.cleanup != null && active.cleanup.alreadyClosed(screen);
    }

    /** Called only at GuiContainer.onGuiClosed's original container callback. */
    public static boolean suspending(GuiContainer screen, Container container) {
        var frame = active;
        if (retired != null && retired.suspends(screen, container)) return true;
        return frame != null && (frame.entering && frame.guard.suspends(screen, container) || frame.cleanup != null && frame.cleanup.suspends(screen, container));
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

    private static final class Frame {
        final Session session;
        final PreviewScreenGuard guard;
        final @Nullable GuiScreen source, target;
        final boolean entering;
        final ObjectArrayList<SavedSlot> slots;
        final @Nullable PreviewScreenGuard.Retired cleanup;
        boolean postSeen;
        @Nullable GuiOpenEvent opening;

        Frame(Session session, PreviewScreenGuard guard, @Nullable GuiScreen source, @Nullable GuiScreen target, boolean entering, ObjectArrayList<SavedSlot> slots) {
            this.session = session;
            this.guard = guard;
            this.source = source;
            this.target = target;
            this.entering = entering;
            this.slots = slots;
            cleanup = target == null && retired != null && retired.blocks(source) ? retired : null;
        }

        void rollback() {
            if (!session.ownerCurrent() || Minecraft.getMinecraft().currentScreen != source) return;
            for (var saved : slots) if (session.container.inventorySlots.contains(saved.slot)) saved.restore();
        }
    }

    public static final class Session {
        private final StructurePreviews service;
        private final EntityPlayer player;
        private final Object world;
        private final Object connection;
        private final Container container;
        private final int window;
        private final @Nullable GuiScreen parent;
        private final ObjectArrayList<SavedSlot> originalSlots = new ObjectArrayList<>();
        private StructurePreviewGui viewer;
        private boolean returned;

        private Session(StructurePreviews service) {
            var mc = Minecraft.getMinecraft();
            this.service = service;
            player = mc.player;
            world = mc.world;
            connection = mc.getConnection();
            container = player.openContainer;
            window = container.windowId;
            parent = mc.currentScreen;
            // A nested GuiOpenEvent starts while AE's outer switch already hid slots. Restore the
            // outer-owned positions before capturing the replacement's own native save state.
            if (active != null) {
                active.guard.revoke();
                if (!active.entering && active.target == active.session.parent && mc.currentScreen == active.target
                    && active.session.container == container && active.session.ownerCurrent())
                    active.session.restoreOriginalSlots();
                else active.rollback();
            }
            for (var slot : container.inventorySlots) originalSlots.add(new SavedSlot(slot));
        }

        public boolean ownerCurrent() {
            var mc = Minecraft.getMinecraft();
            return mc.player == player && mc.world == world && mc.getConnection() == connection
                && player.openContainer == container && container.windowId == window && player.isEntityAlive() && player.getHealth() > 0 && !service.closed();
        }

        public boolean viewerCurrent(StructurePreviewGui screen) {
            return viewer == screen && Minecraft.getMinecraft().currentScreen == screen && ownerCurrent();
        }

        public boolean initCurrent(StructurePreviewGui screen) {
            return viewerCurrent(screen);
        }

        public boolean open(StructurePreviewGui screen) {
            if (viewer != null || !ownerCurrent() || Minecraft.getMinecraft().currentScreen != parent) return false;
            viewer = screen;
            var previous = active;
            if (previous != null) {
                previous.guard.revoke();
                previous.rollback();
            }
            var frame = frame(screen, true);
            active = frame;
            boolean completed = false;
            Throwable failure = null;
            try {
                if (parent instanceof AEBaseGui<?> ae && screen.getContainer() == container) ae.switchToScreen(screen);
                else Minecraft.getMinecraft().displayGuiScreen(screen);
                completed = true;
            } catch (RuntimeException | Error caught) {
                failure = caught;
            } finally {
                if (active == frame) active = previous;
                keepNestedViewerSuspended();
                if (Minecraft.getMinecraft().currentScreen == screen && frame.guard.displayedOwnerRetired())
                    retire(frame, screen);
                if (Minecraft.getMinecraft().currentScreen != screen) frame.rollback();
                if (!completed || !viewerCurrent(screen) || !frame.guard.displayedRequested()) {
                    try {
                        screen.releasePreview();
                    } catch (RuntimeException | Error caught) {
                        failure = PreviewBlockModelCache.merge(failure, caught);
                    }
                }
            }
            PreviewBlockModelCache.rethrow(failure);
            return viewerCurrent(screen) && frame.guard.displayedRequested();
        }

        private Frame frame(@Nullable GuiScreen target, boolean entering) {
            var slots = new ObjectArrayList<SavedSlot>();
            for (var slot : container.inventorySlots) slots.add(new SavedSlot(slot));
            var before = state(service, entering);
            return new Frame(this, new PreviewScreenGuard(before, target, () -> state(service, entering)), Minecraft.getMinecraft().currentScreen, target, entering, slots);
        }

        private void restoreOriginalSlots() {
            for (var saved : originalSlots) if (container.inventorySlots.contains(saved.slot)) saved.restore();
        }

        private void keepNestedViewerSuspended() {
            if (Minecraft.getMinecraft().currentScreen instanceof StructurePreviewGui next && next != viewer && next.getContainer() == container)
                next.suspendNativeSlots();
        }

        public void suspendNativeSlots(StructurePreviewGui screen) {
            if (screen.getContainer() != container || !viewerCurrent(screen)) return;
            for (var saved : originalSlots) if (container.inventorySlots.contains(saved.slot)) saved.suspend();
        }

        private void closeLocal(GuiScreen expected) {
            var mc = Minecraft.getMinecraft();
            if (mc.currentScreen != expected) return;
            var previous = active;
            if (previous != null) {
                previous.guard.revoke();
                previous.rollback();
            }
            var frame = frame(null, false);
            active = frame;
            try {
                mc.displayGuiScreen(null);
            } finally {
                if (active == frame) active = previous;
            }
        }

        public void back() {
            var mc = Minecraft.getMinecraft();
            if (returned || viewer == null || mc.currentScreen != viewer) return;
            if (!ownerCurrent() || parent == null) {
                closeLocal(viewer);
                returned = mc.currentScreen != viewer;
                return;
            }
            var previous = active;
            if (previous != null) {
                previous.guard.revoke();
                previous.rollback();
            }
            var frame = frame(parent, false);
            active = frame;
            try {
                if (parent instanceof AEBaseGui<?> ae && viewer.getContainer() == container) viewer.switchToScreen(ae);
                else mc.displayGuiScreen(parent);
            } finally {
                if (active == frame) active = previous;
                keepNestedViewerSuspended();
                if (mc.currentScreen == parent && frame.guard.displayedOwnerRetired()) retire(frame, parent);
                if (mc.currentScreen != parent) frame.rollback();
            }
            if (mc.currentScreen == parent && ownerCurrent() && frame.guard.displayedRequested()) {
                returned = true;
                // AE consumes its private saved array even if a prior return was canceled. This
                // Session owns the original exact slot positions across all return retries.
                restoreOriginalSlots();
                if (parent instanceof AEBaseGui<?> ae) ae.returnFromSubScreen(viewer);
            }
        }
    }
}
