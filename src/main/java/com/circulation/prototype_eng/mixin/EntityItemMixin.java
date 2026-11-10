package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import com.circulation.prototype_eng.research.download.DownloadPlayerInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Retains the original entity while its exact reward leaf still awaits durable confirmation.
 */
@Mixin(EntityItem.class)
public abstract class EntityItemMixin extends Entity {
    public EntityItemMixin(World worldIn) {
        super(worldIn);
    }

    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "onCollideWithPlayer", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdPickup(EntityPlayer player, CallbackInfo callback) {
        if (!this.world.isRemote && (DownloadPlayerInventory.pending(player)
            || RewardItemProtection.isProtected(this.getItem()))) callback.cancel();
    }

    @Inject(method = "onUpdate", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdDelivery(CallbackInfo callback) {
        if (!this.world.isRemote && RewardItemProtection.isProtected(this.getItem())) callback.cancel();
    }

    @Inject(method = "attackEntityFrom", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        if (!this.world.isRemote && RewardItemProtection.isProtected(this.getItem())) callback.setReturnValue(false);
    }

    @Inject(method = "combineItems", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdMerge(EntityItem other, CallbackInfoReturnable<Boolean> callback) {
        if (!this.world.isRemote && (RewardItemProtection.isProtected(this.getItem())
            || RewardItemProtection.isProtected(other.getItem()))) callback.setReturnValue(false);
    }
}
