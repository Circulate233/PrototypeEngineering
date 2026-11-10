package com.circulation.prototype_eng.storage;

import ae2.api.stacks.AEKeyType;
import ae2.helpers.externalstorage.GenericStackItemStorage;

/** AE's native transfer behavior with accurate per-side slot capacity metadata. */
public final class HatchItemHandler extends GenericStackItemStorage {
    private final HatchInventoryPort inventory;

    public HatchItemHandler(HatchInventoryPort inventory) {
        super(inventory);
        this.inventory = inventory;
    }

    @Override
    public int getSlotLimit(int slot) {
        return (int) Math.min(Integer.MAX_VALUE, inventory.getCapacity(slot, AEKeyType.items()));
    }
}
