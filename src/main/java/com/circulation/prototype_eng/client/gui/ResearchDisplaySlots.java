package com.circulation.prototype_eng.client.gui;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.client.gui.StackWithBounds;
import ae2.container.slot.InaccessibleSlot;
import ae2.helpers.externalstorage.GenericStackInv;
import ae2.util.ConfigInventory;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.awt.Rectangle;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class ResearchDisplaySlots {
    private final ObjectArrayList<Entry> slots = new ObjectArrayList<>();
    private int count, guiLeft, guiTop;
    private double scale, clipLeft, clipTop, clipRight, clipBottom;

    void beginFrame(double scale, int guiLeft, int guiTop) {
        for (int i = 0; i < count; i++) {
            var entry = slots.get(i);
            entry.slot.setSlotEnabled(false);
            entry.inventory.setStack(0, null);
            entry.slot.onSlotChanged();
        }
        count = 0;
        this.scale = scale;
        this.guiLeft = guiLeft;
        this.guiTop = guiTop;
    }

    void clip(double x, double y, double width, double height) {
        clipLeft = x;
        clipTop = y;
        clipRight = x + width;
        clipBottom = y + height;
    }

    @Nullable InaccessibleSlot add(AEKey key, long amount, double x, double y, double size) {
        int left = guiLeft + (int) Math.ceil(Math.max(x, clipLeft) * scale);
        int top = guiTop + (int) Math.ceil(Math.max(y, clipTop) * scale);
        int right = guiLeft + (int) Math.ceil(Math.min(x + size, clipRight) * scale);
        int bottom = guiTop + (int) Math.ceil(Math.min(y + size, clipBottom) * scale);
        if (right <= left || bottom <= top) return null;
        if (count == slots.size()) slots.add(new Entry());
        var entry = slots.get(count++);
        entry.inventory.setStack(0, new GenericStack(key, amount));
        entry.slot.onSlotChanged();
        entry.slot.xPos = (int) Math.ceil(x * scale);
        entry.slot.yPos = (int) Math.ceil(y * scale);
        entry.bounds.setBounds(left, top, right - left, bottom - top);
        entry.slot.setSlotEnabled(true);
        return entry.slot;
    }

    @Nullable Slot find(double mouseX, double mouseY) {
        for (int i = count - 1; i >= 0; i--) {
            var entry = slots.get(i);
            if (entry.bounds.contains(mouseX, mouseY)) return entry.slot;
        }
        return null;
    }

    @Nullable StackWithBounds stackAt(double mouseX, double mouseY) {
        for (int i = count - 1; i >= 0; i--) {
            var entry = slots.get(i);
            if (entry.bounds.contains(mouseX, mouseY))
                return new StackWithBounds(entry.inventory.getStack(0), new Rectangle(entry.bounds));
        }
        return null;
    }

    void draw(Consumer<Slot> renderer) {
        for (int i = 0; i < count; i++) renderer.accept(slots.get(i).slot);
    }

    boolean owns(@Nullable Slot slot) {
        for (int i = 0; i < slots.size(); i++)
            if (slots.get(i).slot == slot) return true;
        return false;
    }

    private static final class Entry {
        private final DisplayInventory inventory = new DisplayInventory();
        private final InaccessibleSlot slot = new InaccessibleSlot(inventory.createGuiWrapper(), 0);
        private final Rectangle bounds = new Rectangle();

        private Entry() {
            slot.slotNumber = -1;
            slot.setDraggable(false);
        }
    }

    private static final class DisplayInventory extends GenericStackInv {
        private DisplayInventory() {
            super(null, ConfigInventory.Mode.CONFIG_STACKS, 1);
        }

        @Override
        public long getMaxAmount(AEKey key) {
            return Long.MAX_VALUE;
        }
    }
}
