package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Keeps tagged slots in the original inventory while death still drops every ordinary stack. */
@Mixin(InventoryPlayer.class)
public abstract class InventoryPlayerMixin {
    @Shadow public EntityPlayer player;

    @Redirect(method = "dropAllItems", at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;", remap = false),
        require = 1, allow = 1)
    private Object prototype_eng$retainRewardOnDeath(List<ItemStack> inventory, int slot) {
        var stack = inventory.get(slot);
        return !player.world.isRemote && RewardItemProtection.isProtected(stack) ? ItemStack.EMPTY : stack;
    }

    @Inject(method = "decrementAnimations", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$pauseInventoryCallbacks(CallbackInfo callback) {
        // Custom onUpdate/onArmorTick callbacks receive the player and may consume other inventory
        // slots. Pause these inventory callbacks, including indirect mutations, rather than player ticks.
        if (!player.world.isRemote && RewardItemProtection.hasProtectedInventory(player)) callback.cancel();
    }
}
