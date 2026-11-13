package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.client.preview.PreviewEmbeddedInput;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * HEI receives cancelled Forge events too; embedded preview actions must precede ingredient lookup.
 */
@Pseudo
@Mixin(targets = "mezz.jei.input.InputHandler", remap = false)
public abstract class HeiPreviewInputMixin {
    @Inject(method = "handleMouseEvent", at = @At("HEAD"), cancellable = true, remap = false)
    private void prototype_eng$previewBeforeIngredientLookup(GuiScreen guiScreen, int mouseX, int mouseY, CallbackInfoReturnable<Boolean> callback) {
        if (PreviewEmbeddedInput.INSTANCE.mouse(guiScreen, mouseX, mouseY)) callback.setReturnValue(true);
    }
}
