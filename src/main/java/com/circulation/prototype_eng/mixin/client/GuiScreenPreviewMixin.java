package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A return parent's InitGuiEvent.Pre cannot rebind an owner revoked by its own callback. */
@Mixin(GuiScreen.class)
public abstract class GuiScreenPreviewMixin {
    @Redirect(method = "setWorldAndResolution", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/eventhandler/EventBus;post(Lnet/minecraftforge/fml/common/eventhandler/Event;)Z", remap = false), require = 2, allow = 2)
    private boolean prototype_eng$postPreviewInit(EventBus bus, Event event) { return PreviewScreenTransitions.postInit(bus, event); }
}
