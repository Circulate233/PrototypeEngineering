package com.circulation.prototype_eng.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;

/**
 * PM's pointed block, with player-position fallback, only used to locate display intent.
 */
public final class StructurePreviewScreensAnchor {
    private StructurePreviewScreensAnchor() {
    }

    public static BlockPos current() {
        var mc = Minecraft.getMinecraft();
        var hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) return hit.getBlockPos().toImmutable();
        return mc.player == null ? BlockPos.ORIGIN : mc.player.getPosition();
    }
}
