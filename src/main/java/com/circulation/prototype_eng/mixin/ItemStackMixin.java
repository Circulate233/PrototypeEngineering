package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers vanilla player tool, shield, armor, falling-anvil and elytra durability entry points. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "damageItem", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$retainPlayerDurability(int amount, EntityLivingBase entity, CallbackInfo callback) {
        if (entity instanceof EntityPlayer && !entity.world.isRemote
            && RewardItemProtection.isProtected((ItemStack) (Object) this)) callback.cancel();
    }
}
