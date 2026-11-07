package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.research.reward.RewardPlayerInventory;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Transfers surviving evidence before Clone consumers inspect the old player inventory. */
@Mixin(EntityPlayerMP.class)
public abstract class EntityPlayerMPMixin {
    @Inject(method = "copyFrom", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/event/ForgeEventFactory;onPlayerClone"
        + "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/entity/player/EntityPlayer;Z)V", remap = false), require = 1, allow = 1)
    private void prototype_eng$transferRewards(EntityPlayerMP original, boolean keepEverything, CallbackInfo callback) {
        RewardPlayerInventory.transferProtected(original, (EntityPlayerMP) (Object) this);
    }
}
