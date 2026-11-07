package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntityHopper;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both the block hopper and hopper minecart use this entry before copying or shrinking a drop. */
@Mixin(TileEntityHopper.class)
public abstract class TileEntityHopperMixin {
    @Inject(method = "putDropInInventoryAllSlots", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void prototype_eng$retainRewardDrop(@Nullable IInventory source, IInventory destination,
                                                      @Nullable EntityItem entity, CallbackInfoReturnable<Boolean> callback) {
        if (entity != null && !entity.world.isRemote && RewardItemProtection.isProtected(entity.getItem())) {
            callback.setReturnValue(false);
        }
    }
}
