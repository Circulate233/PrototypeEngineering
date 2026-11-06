package com.circulation.prototype_eng.api.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

/** Stable slots over a multi-type balance. Reads show committed contents; writes respect all holds. */
public interface SlottedResourceStorage extends ResourceStorage {
    int size();
    @Nullable GenericStack getStack(int slot);
    long getAmount(int slot);
    long availableAmount(int slot);
    long getCapacity(AEKeyType type);
    long getMaxAmount(AEKey key);
    boolean isSupportedType(AEKeyType type);
    boolean isAllowedIn(int slot, AEKey key);
    long insert(int slot, AEKey key, long amount, Actionable mode);
    long extract(int slot, AEKey key, long amount, Actionable mode);

    /** Atomic compare-and-replace; refuses any change touching reserved quantity or capacity. */
    boolean tryReplaceSlot(int slot, @Nullable GenericStack expected, @Nullable GenericStack next);

    /** Destructive delta read for an external host's single synchronization owner. */
    @SuppressWarnings("unused")
    int[] drainChangedSlots();
}
