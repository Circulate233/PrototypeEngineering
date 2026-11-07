package com.circulation.prototype_eng.item;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import net.minecraft.util.EnumFacing;

import java.util.Objects;

/** PM's front/twist interaction rules, independent of world and item initialization. */
public final class OrientationToolOrientations {
    private OrientationToolOrientations() { }

    public static BlockOrientation apply(BlockOrientation current, EnumFacing clickedSide, boolean sneaking) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(clickedSide);
        var front = current.getSide(RelativeSide.FRONT);
        if (sneaking) { return next(current); }
        return clickedSide == front ? current.rotateClockwiseAround(front) : canonical(clickedSide);
    }

    /** Original order: four clockwise twists, then the next vanilla facing. */
    public static BlockOrientation next(BlockOrientation current) {
        Objects.requireNonNull(current);
        var front = current.getSide(RelativeSide.FRONT);
        var rotated = current.rotateClockwiseAround(front);
        return rotated == canonical(front)
            ? canonical(EnumFacing.VALUES[(front.ordinal() + 1) % EnumFacing.VALUES.length]) : rotated;
    }

    /** PM differs from AE2's default for DOWN: its canonical top is SOUTH. */
    public static BlockOrientation canonical(EnumFacing front) {
        Objects.requireNonNull(front);
        return BlockOrientation.get(front, switch (front) {
            case UP -> EnumFacing.NORTH;
            case DOWN -> EnumFacing.SOUTH;
            default -> EnumFacing.UP;
        });
    }
}
