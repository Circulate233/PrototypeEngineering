package com.circulation.prototype_eng.item;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.structure.StructureAnchorRotation;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

public final class OrientationToolOrientations {
    public static final double EDGE = .2;

    private OrientationToolOrientations() {
    }

    public static BlockOrientation apply(BlockOrientation current, EnumFacing clickedSide, Part part) {
        var front = current.getSide(RelativeSide.FRONT);
        if (part == Part.CORNER) return current;
        if (part == Part.CENTER && clickedSide == front) return current.rotateClockwiseAround(front);
        return face(current, target(clickedSide, part));
    }

    public static @Nullable BlockOrientation externalTarget(StructureAnchorRotation rotation, BlockOrientation current,
                                                            EnumFacing clickedSide, Part part) {
        if (part == Part.CORNER) return null;
        var target = rotation.project(apply(current, clickedSide, part));
        return target == current ? null : target;
    }

    /**
     * Rigid quarter/half turns preserve the existing top instead of resetting the twist.
     */
    private static BlockOrientation face(BlockOrientation current, EnumFacing target) {
        var front = current.getSide(RelativeSide.FRONT);
        if (target == front) return current;
        if (target == front.getOpposite()) {
            var axis = current.getSide(RelativeSide.TOP);
            return current.rotateClockwiseAround(axis).rotateClockwiseAround(axis);
        }
        var a = front.getDirectionVec();
        var b = target.getDirectionVec();
        var axis = a.crossProduct(b);
        return current.rotateClockwiseAround(EnumFacing.getFacingFromVector(axis.getX(), axis.getY(), axis.getZ()));
    }

    public static EnumFacing up(EnumFacing face) {
        return canonical(face).getSide(RelativeSide.TOP);
    }

    public static EnumFacing right(EnumFacing face) {
        // AE's LEFT is the viewer's right when looking at the face from outside the block.
        return canonical(face).getSide(RelativeSide.LEFT);
    }

    public static EnumFacing target(EnumFacing face, Part part) {
        return switch (part) {
            case CENTER, CORNER -> face;
            case TOP -> up(face);
            case RIGHT -> right(face);
            case BOTTOM -> up(face).getOpposite();
            case LEFT -> right(face).getOpposite();
        };
    }

    public static Part part(EnumFacing face, double hitX, double hitY, double hitZ) {
        if (!Double.isFinite(hitX) || !Double.isFinite(hitY) || !Double.isFinite(hitZ)
            || hitX < 0 || hitX > 1 || hitY < 0 || hitY > 1 || hitZ < 0 || hitZ > 1) return Part.CORNER;
        var right = right(face);
        var up = up(face);
        double u = .5 + (hitX - .5) * right.getXOffset() + (hitY - .5) * right.getYOffset() + (hitZ - .5) * right.getZOffset();
        double v = .5 + (hitX - .5) * up.getXOffset() + (hitY - .5) * up.getYOffset() + (hitZ - .5) * up.getZOffset();
        boolean left = u < EDGE, rightEdge = u > 1 - EDGE, bottom = v < EDGE, top = v > 1 - EDGE;
        if ((left || rightEdge) && (bottom || top)) return Part.CORNER;
        if (left) return Part.LEFT;
        if (rightEdge) return Part.RIGHT;
        if (bottom) return Part.BOTTOM;
        return top ? Part.TOP : Part.CENTER;
    }

    /**
     * Unit-block face coordinates; u increases to the viewer's right and v towards its top.
     */
    public static Vec3d point(EnumFacing face, double u, double v) {
        var right = right(face);
        var up = up(face);
        return new Vec3d(.5 + .5 * face.getXOffset() + (u - .5) * right.getXOffset() + (v - .5) * up.getXOffset(),
            .5 + .5 * face.getYOffset() + (u - .5) * right.getYOffset() + (v - .5) * up.getYOffset(),
            .5 + .5 * face.getZOffset() + (u - .5) * right.getZOffset() + (v - .5) * up.getZOffset());
    }

    /**
     * PM differs from AE2's default for DOWN: its canonical top is SOUTH.
     */
    public static BlockOrientation canonical(EnumFacing front) {
        return BlockOrientation.get(front, switch (front) {
            case UP -> EnumFacing.NORTH;
            case DOWN -> EnumFacing.SOUTH;
            default -> EnumFacing.UP;
        });
    }

    public enum Part {
        CENTER, TOP, RIGHT, BOTTOM, LEFT, CORNER;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final Part[] VALUES = values();
    }
}
