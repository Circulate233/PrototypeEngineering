package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.client.render.MachineControllerRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Run before CMH's default-priority HEAD hook and protect only our registered replacement TESR.
 */
@Mixin(value = TileEntityRendererDispatcher.class, priority = 1100)
public abstract class TileEntityRendererDispatcherMachineMixin {
    @Redirect(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;getMaxRenderDistanceSquared()D"))
    private double prototype_eng$typedModelDistance(TileEntity tile) {
        return MachineControllerRenderer.nativeMaximumDistanceSquared(tile);
    }

    @Shadow
    public abstract <T extends TileEntity> @Nullable TileEntitySpecialRenderer<T> getRenderer(Class<? extends TileEntity> tileClass);

    @Inject(method = "getRenderer(Lnet/minecraft/tileentity/TileEntity;)Lnet/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer;", at = @At("HEAD"), cancellable = true)
    private <T extends TileEntity> void prototype_eng$keepReplacement(@Nullable TileEntity tile, CallbackInfoReturnable<TileEntitySpecialRenderer<T>> callback) {
        if (tile == null || tile.isInvalid()) return;
        TileEntitySpecialRenderer<T> renderer = getRenderer(tile.getClass());
        if (renderer instanceof MachineControllerRenderer<?>) callback.setReturnValue(renderer);
    }
}
