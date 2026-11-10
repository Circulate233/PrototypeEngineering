package com.circulation.prototype_eng.storage;

import ae2.api.behaviors.GenericStackDisplayInventory;
import ae2.api.inventories.InternalInventory;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Bounded AE display slots backed by the container's atomic, versioned resource window.
 */
public final class HatchGuiInventory implements InternalInventory, GenericStackDisplayInventory {
    private final int cells;
    private final boolean client;
    private final IntFunction<@Nullable GenericStack> display;

    public HatchGuiInventory(int cells, boolean client, IntFunction<@Nullable GenericStack> display) {
        if (cells < 1 || cells > 72) throw new IllegalArgumentException("Invalid hatch display size");
        this.cells = cells;
        this.client = client;
        this.display = Objects.requireNonNull(display);
    }

    @Override
    public int size() {
        return cells;
    }

    @Override
    public int getSlotLimit(int slot) {
        Objects.checkIndex(slot, size());
        return Integer.MAX_VALUE;
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        GenericStack stack = displayStack(slot);
        if (stack != null && stack.what() instanceof AEItemKey item
            && stack.amount() > 0 && stack.amount() <= item.getMaxStackSize()) {
            return item.toStack((int) stack.amount());
        }
        return GenericStack.wrapInItemStack(stack);
    }

    @Override
    public void setItemDirect(int slot, @NotNull ItemStack stack) {
        if (!client) {
            throw new UnsupportedOperationException("Server hatch GUI writes require container actions");
        }
        // SPacketSetSlot/WindowItems carry no view revision. Only GuiSync updates resource display.
        Objects.checkIndex(slot, size());
    }

    @Nullable
    private GenericStack displayStack(int slot) {
        return display.apply(Objects.checkIndex(slot, size()));
    }

    @Override
    public boolean hasGenericDisplayStack(int slot) {
        return displayStack(slot) != null;
    }

    @Override
    @Nullable
    public AEKey getDisplayKey(int slot) {
        GenericStack stack = displayStack(slot);
        return stack == null ? null : stack.what();
    }

    @Override
    public long getDisplayAmount(int slot) {
        GenericStack stack = displayStack(slot);
        return stack == null ? 0 : stack.amount();
    }
}
