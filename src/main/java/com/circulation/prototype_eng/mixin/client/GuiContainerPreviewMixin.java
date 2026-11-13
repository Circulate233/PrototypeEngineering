package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.client.gui.GuiStructurePreview;
import com.circulation.prototype_eng.client.preview.GuiPreviewNative;
import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Only a preview's local draw container skips server-pointer assignment; only its scoped parent keeps cursor ownership.
 */
@Mixin(GuiContainer.class)
public abstract class GuiContainerPreviewMixin implements GuiPreviewNative.Slots {
    @Shadow
    protected abstract void drawSlot(Slot slot);

    @Override
    public void prototype_eng$renderSlot(Slot slot) {
        drawSlot(slot);
    }

    @Redirect(method = "initGui", at = @At(value = "FIELD", target = "Lnet/minecraft/client/entity/EntityPlayerSP;openContainer:Lnet/minecraft/inventory/Container;", opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void prototype_eng$keepOriginalWindow(EntityPlayerSP player, Container local) {
        if (!((Object) this instanceof GuiStructurePreview<?>))
            player.openContainer = local;
    }

    @Redirect(method = "onGuiClosed", at = @At(value = "INVOKE", target = "Lnet/minecraft/inventory/Container;onContainerClosed(Lnet/minecraft/entity/player/EntityPlayer;)V"), require = 1, allow = 1)
    private void prototype_eng$suspendPreviewParent(Container container, EntityPlayer player) {
        if (!PreviewScreenTransitions.suspending((GuiContainer) (Object) this, container))
            container.onContainerClosed(player);
    }
}
