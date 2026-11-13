package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.util.EnumFacing;

/**
 * Visual clockwise convention: NORTH around UP becomes EAST.
 */
public final class ProjectionOrientations {
    private ProjectionOrientations() {
    }

    public static BlockOrientation rotate(BlockOrientation orientation, EnumFacing axis) {
        // AE2 v1.1.0 uses the right-handed positive axis; the viewer's clockwise key uses the opposite sign.
        return orientation.rotateClockwiseAround(axis.getOpposite());
    }
}
