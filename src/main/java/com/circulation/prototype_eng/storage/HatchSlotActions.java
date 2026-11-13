package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Cursor transactions against real quantities, never against an ItemStack used only for display.
 */
public final class HatchSlotActions {
    private HatchSlotActions() {
    }

    /**
     * Caller authorizes the owner/player action; only unreserved contents of this exact type are discarded.
     */
    public static boolean clearAvailable(SlottedResourceStorage inventory, AEKeyType type) {
        if (!inventory.context().tryOrdinaryLock()) {
            return false;
        }
        try {
            if (!inventory.isValid()) {
                return false;
            }
            boolean changed = false;
            for (int slot = 0; slot < inventory.size(); slot++) {
                var stack = inventory.getStack(slot);
                if (stack != null && stack.what().getType() == type) {
                    long available = inventory.availableAmount(slot);
                    changed |= available > 0 && inventory.extract(slot, stack.what(), available, Actionable.MODULATE) > 0;
                }
            }
            return changed;
        } finally {
            inventory.context().unlock();
        }
    }

    /**
     * Inserts the resource represented by an AE package and returns its uninserted amount.
     */
    @Nullable
    public static GenericStack insertPackage(SlottedResourceStorage inventory, int slot, GenericStack packaged) {
        if (slot < -1) throw new IllegalArgumentException("Invalid resource hatch slot");
        if (packaged.amount() <= 0) return packaged;
        inventory.context().lock();
        try {
            if (!inventory.isValid() || !inventory.isSupportedType(packaged.what().getType())) return packaged;
            if (slot >= 0 && !inventory.isAllowedIn(slot, packaged.what())) return packaged;
            long inserted = slot >= 0
                ? inventory.insert(slot, packaged.what(), packaged.amount(), Actionable.MODULATE)
                : inventory.insert(packaged.what(), packaged.amount(), Actionable.MODULATE);
            if (inserted <= 0) return packaged;
            return inserted == packaged.amount() ? null : new GenericStack(packaged.what(), packaged.amount() - inserted);
        } finally {
            inventory.context().unlock();
        }
    }

    @Nullable
    public static GenericStack click(SlottedResourceStorage inventory, int slot, @Nullable GenericStack carried,
                                     long cursorLimit, boolean acceptsInput, boolean rightClick) {
        inventory.context().lock();
        try {
            return clickLocked(inventory, slot, carried, cursorLimit, acceptsInput, rightClick);
        } finally {
            inventory.context().unlock();
        }
    }

    @Nullable
    private static GenericStack clickLocked(SlottedResourceStorage inventory, int slot, @Nullable GenericStack carried,
                                            long cursorLimit, boolean acceptsInput, boolean rightClick) {
        if (!inventory.isValid() || cursorLimit <= 0) {
            return carried;
        }
        GenericStack stored = inventory.getStack(slot);
        if (carried == null) {
            if (stored == null) {
                return null;
            }
            long amount = Math.min(cursorLimit, rightClick ? stored.amount() / 2 + stored.amount() % 2 : stored.amount());
            long extracted = inventory.extract(slot, stored.what(), amount, Actionable.MODULATE);
            return extracted == 0 ? null : new GenericStack(stored.what(), extracted);
        }
        boolean sameKey = stored != null && stored.what().equals(carried.what());
        if (!acceptsInput) {
            if (!sameKey) {
                return carried;
            }
            long amount = Math.max(0, cursorLimit - carried.amount());
            long extracted = inventory.extract(slot, carried.what(), amount, Actionable.MODULATE);
            return new GenericStack(carried.what(), carried.amount() + extracted);
        }
        if (stored == null || sameKey) {
            long inserted = inventory.insert(slot, carried.what(), rightClick ? 1 : carried.amount(), Actionable.MODULATE);
            long remaining = carried.amount() - inserted;
            return remaining == 0 ? null : new GenericStack(carried.what(), remaining);
        }
        // A swap must fit both destinations completely before either side changes.
        if (!rightClick && stored.amount() <= cursorLimit
            && inventory.isAllowedIn(slot, carried.what()) && carried.amount() <= inventory.getMaxAmount(carried.what())
            && inventory.tryReplaceSlot(slot, stored, carried)) {
            return stored;
        }
        return carried;
    }
}
