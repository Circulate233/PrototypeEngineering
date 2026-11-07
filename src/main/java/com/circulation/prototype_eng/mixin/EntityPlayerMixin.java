package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.research.download.DownloadPlayerInventory;
import com.circulation.prototype_eng.research.reward.RewardPlayerInventory;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Preserves pending reward evidence through the actual curse-removal and player NBT paths.
 */
@Mixin(EntityPlayer.class)
public abstract class EntityPlayerMixin {
    @Redirect(method = "destroyVanishingCursedItems", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/enchantment/EnchantmentHelper;hasVanishingCurse(Lnet/minecraft/item/ItemStack;)Z"), require = 1, allow = 1)
    private boolean prototype_eng$retainCursedReward(ItemStack stack) {
        var player = (EntityPlayer) (Object) this;
        return (player.world.isRemote || !DownloadPlayerInventory.protectedStack(player, stack)) && EnchantmentHelper.hasVanishingCurse(stack);
    }

    @Inject(method = "writeEntityToNBT", at = @At("TAIL"), require = 1, allow = 1)
    private void prototype_eng$saveRewardCursor(NBTTagCompound compound, CallbackInfo callback) {
        var player = (EntityPlayer) (Object) this;
        RewardPlayerInventory.writeProtectedCursor(player, compound);
        DownloadPlayerInventory.writeCursor(player, compound);
    }

    @Inject(method = "readEntityFromNBT", at = @At("TAIL"), require = 1, allow = 1)
    private void prototype_eng$restoreRewardCursor(NBTTagCompound compound, CallbackInfo callback) {
        var player = (EntityPlayer) (Object) this;
        RewardPlayerInventory.readProtectedCursor(player, compound);
        DownloadPlayerInventory.readCursor(player, compound);
    }

    @Inject(method = "readEntityFromNBT", at = @At("HEAD"), require = 1, allow = 1)
    private void prototype_eng$reconcileRewardJournal(NBTTagCompound compound, CallbackInfo callback) {
        RewardPlayerInventory.reconcileDeliveryJournal((EntityPlayer) (Object) this, compound);
    }
}
