package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.research.download.DownloadPlayerInventory;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.ISpecialArmor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Retains normal armor absorption while skipping only the protected player's stack damage.
 */
@Mixin(value = ISpecialArmor.ArmorProperties.class, remap = false)
public abstract class ArmorPropertiesMixin {
    @Redirect(method = "applyArmor", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/common/ISpecialArmor;damageArmor"
        + "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/DamageSource;II)V", remap = false),
        require = 1, allow = 1)
    private static void prototype_eng$retainSpecialArmor(ISpecialArmor armor, EntityLivingBase entity, ItemStack stack,
                                                         DamageSource source, int damage, int slot) {
        if (!(entity instanceof EntityPlayer player && !entity.world.isRemote && DownloadPlayerInventory.protectedStack(player, stack))) {
            armor.damageArmor(entity, stack, source, damage, slot);
        }
    }
}
