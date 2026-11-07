package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Closing, death and logout retain the protected cursor in its existing player/NBT owner. */
@Mixin(Container.class)
public abstract class ContainerMixin {
    @Inject(method = "onContainerClosed", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$retainRewardCursor(EntityPlayer player, CallbackInfo callback) {
        if (!player.world.isRemote && RewardItemProtection.isProtected(player.inventory.getItemStack())) callback.cancel();
    }
}
