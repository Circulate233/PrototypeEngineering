package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.research.download.DownloadPlayerInventory;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A use started before material capture must not later consume the pending inventory.
 */
@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBaseMixin {
    @Inject(method = "updateActiveHand", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdMaterialUse(CallbackInfo callback) {
        if ((Object) this instanceof EntityPlayer player && DownloadPlayerInventory.pending(player)) callback.cancel();
    }
}
