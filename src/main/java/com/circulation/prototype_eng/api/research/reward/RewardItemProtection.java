package com.circulation.prototype_eng.api.research.reward;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import java.util.Objects;

/**
 * Shared query for hosts that move, consume or update player items. Inspect stacks on their owner
 * thread and leave delivery evidence untouched until the authoritative delivery owner releases it.
 * Presence is a protection marker, not proof of eligibility, successful delivery or global absence.
 */
public final class RewardItemProtection {
    public static final String DELIVERY_TAG = "PrototypeEngineeringRewardDelivery";

    private RewardItemProtection() {
    }

    /**
     * Malformed evidence is also protected; only the delivery owner may validate and release it.
     */
    public static boolean isProtected(ItemStack stack) {
        return !Objects.requireNonNull(stack).isEmpty() && stack.hasTagCompound()
            && Objects.requireNonNull(stack.getTagCompound()).hasKey(DELIVERY_TAG);
    }

    /**
     * Includes armor, offhand and the carried cursor stack, not just the selected hotbar slot.
     */
    public static boolean hasProtectedInventory(EntityPlayer player) {
        var inventory = Objects.requireNonNull(player).inventory;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            if (isProtected(inventory.getStackInSlot(slot))) return true;
        }
        return isProtected(inventory.getItemStack());
    }
}
