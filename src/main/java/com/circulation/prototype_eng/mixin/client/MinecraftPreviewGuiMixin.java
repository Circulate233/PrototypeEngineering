package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.client.event.GuiOpenEvent;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;

/** Final event outcome belongs to one current preview operation; nested newer GUI changes take precedence. */
@Mixin(Minecraft.class)
public abstract class MinecraftPreviewGuiMixin {
    @Redirect(method = "displayGuiScreen", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/eventhandler/EventBus;post(Lnet/minecraftforge/fml/common/eventhandler/Event;)Z", remap = false), require = 1, allow = 1)
    private boolean prototype_eng$postPreviewGui(EventBus bus, Event event) { return PreviewScreenTransitions.post(bus, event); }
    @Redirect(method = "displayGuiScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;onGuiClosed()V"), require = 1, allow = 1)
    private void prototype_eng$closePreviewSourceOnce(GuiScreen old) { if (!PreviewScreenTransitions.alreadyClosed(old)) old.onGuiClosed(); }
    @Inject(method = "displayGuiScreen", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;currentScreen:Lnet/minecraft/client/gui/GuiScreen;", opcode = Opcodes.PUTFIELD), cancellable = true, require = 1, allow = 1)
    private void prototype_eng$holdNewerScreen(GuiScreen target, CallbackInfo callback, @Local(ordinal = 0) GuiOpenEvent event) {
        if (PreviewScreenTransitions.beforeAssign(target, event)) callback.cancel();
    }
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleInput()V"), require = 1, allow = 1)
    private void prototype_eng$holdRevokedScreenInput(GuiScreen screen) throws IOException { if (!PreviewScreenTransitions.blocked(screen)) screen.handleInput(); }
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;updateScreen()V"), require = 1, allow = 1)
    private void prototype_eng$holdRevokedScreenUpdate(GuiScreen screen) { if (!PreviewScreenTransitions.blocked(screen)) screen.updateScreen(); }
    @Redirect(method = "runTickMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleMouseInput()V"), require = 1, allow = 1)
    private void prototype_eng$holdRevokedMouseRoute(GuiScreen screen) throws IOException { if (!PreviewScreenTransitions.blocked(screen)) screen.handleMouseInput(); }
}
