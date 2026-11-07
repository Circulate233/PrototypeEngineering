package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.research.download.DownloadPlayerInventory;
import net.minecraft.enchantment.EnchantmentThorns;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Thorns still damages its attacker; only its subsequent protected stack damage is skipped.
 */
@Mixin(EnchantmentThorns.class)
public abstract class EnchantmentThornsMixin {
    // Forge adds this named helper, which calls either damageItem or ISpecialArmor.damageArmor.
    @Inject(method = "damageArmor", at = @At("HEAD"), remap = false, cancellable = true, require = 1, allow = 1)
    private void prototype_eng$retainThornsArmor(ItemStack stack, int amount, EntityLivingBase entity, CallbackInfo callback) {
        if (entity instanceof EntityPlayer player && !entity.world.isRemote && DownloadPlayerInventory.protectedStack(player, stack))
            callback.cancel();
    }
}
