package com.circulation.prototype_eng.client.render;

import gregtech.client.renderer.IRenderSetup;
import gregtech.client.shader.postprocessing.BloomType;
import gregtech.client.utils.BloomEffectUtil;
import gregtech.client.utils.EffectRenderContext;
import gregtech.client.utils.IBloomEffect;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Loaded only through the exact optional native-mod availability guard. */
final class GregTechMachineBloomHooks implements IRenderSetup, IBloomEffect {
    private static final GregTechMachineBloomHooks INSTANCE = new GregTechMachineBloomHooks();
    private static BloomEffectUtil.@Nullable BloomRenderTicket ticket;

    private GregTechMachineBloomHooks() {
    }

    static boolean available() {
        if (ticket == null || !ticket.isValid())
            ticket = BloomEffectUtil.registerBloomRender(INSTANCE, BloomType.UNREAL, INSTANCE, _ -> true);
        return ticket != null && ticket.isValid() && BloomEffectUtil.getBloomFBO() != null && BloomEffectUtil.getEffectiveBloomLayer() == BloomEffectUtil.getBloomLayer();
    }

    @Override
    public void preDraw(@NotNull BufferBuilder builder) {
    }

    @Override
    public void postDraw(@NotNull BufferBuilder builder) {
    }

    @Override
    public boolean shouldRenderBloomEffect(@NotNull EffectRenderContext context) {
        return MachineModelBloom.hasPending();
    }

    @Override
    public void renderBloomEffect(@NotNull BufferBuilder builder, EffectRenderContext context) {
        MachineModelBloom.draw(new Vec3d(context.cameraX(), context.cameraY(), context.cameraZ()));
    }
}
