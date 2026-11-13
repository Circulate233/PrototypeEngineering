package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.client.render.MachineControllerRenderer;
import com.circulation.prototype_eng.proxy.ClientProxy;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.client.MinecraftForgeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The batch and global-TESR boundaries both feed the same ordered model pass pipeline.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMachineModelsMixin {
    @Unique
    private static void prototype_eng$flush() {
        if (MinecraftForgeClient.getRenderPass() == 0) ((ClientProxy) PrototypeEngineering.proxy).flushMachineModels();
    }

    @Inject(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;drawBatch(I)V", shift = At.Shift.AFTER, remap = false))
    private void prototype_eng$afterBatch(Entity view, ICamera camera, float partialTicks, CallbackInfo callback) {
        prototype_eng$flush();
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void prototype_eng$afterGlobal(Entity view, ICamera camera, float partialTicks, CallbackInfo callback) {
        prototype_eng$flush();
    }

    @Redirect(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;getRenderBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;", remap = false), require = 2)
    private AxisAlignedBB prototype_eng$typedModelBounds(TileEntity tile) {
        return MachineControllerRenderer.nativeBounds(tile);
    }
}
