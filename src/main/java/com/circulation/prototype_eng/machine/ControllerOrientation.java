package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;

/**
 * Vanilla transforms apply to both axes, including the top of an UP/DOWN-facing controller.
 */
public final class ControllerOrientation {
    private ControllerOrientation() {
    }

    /** Keep the placement front, then project the camera's up vector onto that face for its twist. */
    public static BlockOrientation placement(EnumFacing front, float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch);
        double x = -Math.sin(yawRadians) * Math.sin(pitchRadians);
        double y = Math.cos(pitchRadians);
        double z = Math.cos(yawRadians) * Math.sin(pitchRadians);
        var fallback = front == EnumFacing.DOWN ? EnumFacing.fromAngle(yaw).getOpposite() : EnumFacing.fromAngle(yaw);
        var top = front.getAxis() == EnumFacing.Axis.Y ? fallback : EnumFacing.UP;
        double score = 1.0e-12;
        for (var candidate : EnumFacing.VALUES) {
            if (candidate.getAxis() == front.getAxis()) continue;
            double dot = candidate.getXOffset() * x + candidate.getYOffset() * y + candidate.getZOffset() * z;
            if (dot > score) {
                top = candidate;
                score = dot;
            }
        }
        return BlockOrientation.get(front, top);
    }

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
