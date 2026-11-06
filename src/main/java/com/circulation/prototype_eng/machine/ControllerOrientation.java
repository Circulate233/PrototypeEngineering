package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;

/** Vanilla transforms apply to both axes, including the top of an UP/DOWN-facing controller. */
public final class ControllerOrientation {
    private ControllerOrientation() { }

    public static BlockOrientation rotate(BlockOrientation orientation, Rotation rotation) {
        return BlockOrientation.get(rotation.rotate(orientation.getSide(RelativeSide.FRONT)),
            rotation.rotate(orientation.getSide(RelativeSide.TOP)));
    }

    public static BlockOrientation mirror(BlockOrientation orientation, Mirror mirror) {
        return BlockOrientation.get(mirror(orientation.getSide(RelativeSide.FRONT), mirror),
            mirror(orientation.getSide(RelativeSide.TOP), mirror));
    }

    private static EnumFacing mirror(EnumFacing side, Mirror mirror) {
        return switch (mirror) {
            case NONE -> side;
            case LEFT_RIGHT -> side.getAxis() == EnumFacing.Axis.Z ? side.getOpposite() : side;
            case FRONT_BACK -> side.getAxis() == EnumFacing.Axis.X ? side.getOpposite() : side;
        };
    }
}
