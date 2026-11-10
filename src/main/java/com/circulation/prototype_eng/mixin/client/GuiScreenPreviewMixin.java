package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import com.circulation.prototype_eng.client.preview.PreviewNativeGui;
import net.minecraft.item.ItemStack;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A return parent's InitGuiEvent.Pre cannot rebind an owner revoked by its own callback. */
@Mixin(GuiScreen.class)
public abstract class GuiScreenPreviewMixin implements PreviewNativeGui {
    @Shadow protected abstract void renderToolTip(ItemStack stack, int x, int y);

    @Override public void prototype_eng$renderItemTooltip(ItemStack stack, int x, int y) {
        renderToolTip(stack, x, y);
    }

    @Redirect(method = "setWorldAndResolution", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/eventhandler/EventBus;post(Lnet/minecraftforge/fml/common/eventhandler/Event;)Z", remap = false), require = 2, allow = 2)
    private boolean prototype_eng$postPreviewInit(EventBus bus, Event event) {
        return PreviewScreenTransitions.postInit(bus, event);
    }
}
