package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.structure.tool.ConstructionTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import org.jetbrains.annotations.Nullable;

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

    public static @Nullable ConstructionTarget externalAt(ResourceLocation structure, BlockPos anchor) {
        var world = Minecraft.getMinecraft().world;
        if (world == null) return null;
        var target = ConstructionTarget.resolve(world, anchor);
        return target != null && target.external() != null && target.structure().equals(structure) ? target : null;
    }
}
