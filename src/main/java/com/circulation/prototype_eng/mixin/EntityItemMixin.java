package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Retains the original entity while its exact reward leaf still awaits durable confirmation. */
@Mixin(EntityItem.class)
public abstract class EntityItemMixin {
    @Inject(method = "onUpdate", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdDelivery(CallbackInfo callback) {
        var item = (EntityItem) (Object) this;
        if (!item.world.isRemote && RewardItemProtection.isProtected(item.getItem())) callback.cancel();
    }

    @Inject(method = "attackEntityFrom", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        var item = (EntityItem) (Object) this;
        if (!item.world.isRemote && RewardItemProtection.isProtected(item.getItem())) callback.setReturnValue(false);
    }

    @Inject(method = "combineItems", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdMerge(EntityItem other, CallbackInfoReturnable<Boolean> callback) {
        var item = (EntityItem) (Object) this;
        if (!item.world.isRemote && (RewardItemProtection.isProtected(item.getItem())
            || RewardItemProtection.isProtected(other.getItem()))) callback.setReturnValue(false);
    }
}
