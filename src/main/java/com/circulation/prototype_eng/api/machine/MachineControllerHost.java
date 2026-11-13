package com.circulation.prototype_eng.api.machine;

import ae2.api.inventories.InternalInventory;
import ae2.api.orientation.BlockOrientation;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.ObjIntConsumer;
import java.util.function.UnaryOperator;

/**
 * Physical controller interaction and GUI access, independent of the default controller tile.
 */
public interface MachineControllerHost {
    AEBaseTile blockEntity();

    MachineType type();

    /**
     * Real controller storage. Custom hosts may expose their own inventory up to sixteen slots.
     */
    default InternalInventory controllerInventory() {
        return InternalInventory.empty();
    }

    /**
     * Sample only at a joined owner boundary; clients use the container-synchronized count.
     */
    default int refreshControllerInventorySlots() {
        return Math.min(controllerInventory().size(), type().controllerInventorySlots(machine()));
    }

    /**
     * Reads the physical slot, including retained contents in a currently closed slot.
     */
    @SuppressWarnings("unused") // Downstream controller and component code reads physical slots.
    default ItemStack controllerStack(int slot) {
        return controllerInventory().getStackInSlot(slot);
    }

    /**
     * Visits available slots in index order. The callback must not retain or mutate borrowed stacks.
     */
    @SuppressWarnings("unused") // Downstream addons iterate the open controller slots.
    default void forEachControllerStack(ObjIntConsumer<? super ItemStack> visitor) {
        int count = refreshControllerInventorySlots();
        var inventory = controllerInventory();
        for (int slot = 0; slot < count; slot++) visitor.accept(inventory.getStackInSlot(slot), slot);
    }

    @Nullable
    MachineInstance machine();

    /**
     * Exact currently attached or pending-save ownership; null means the host cannot prove it.
     */
    @Nullable
    default PersistentMachineIdentity savedIdentity() {
        return null;
    }

    BlockOrientation orientation();

    boolean formed();

    /**
     * Runs after workers join; callers must recheck their open container inside the callback.
     */
    void readGuiAtBoundary(Runnable read);

    /**
     * Relative transforms use the orientation current at the host's ownership boundary.
     */
    void transformOrientation(UnaryOperator<BlockOrientation> transformation);

    void placedBy(EntityLivingBase placer);

    void controllerBroken();
}
