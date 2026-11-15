package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import com.circulation.prototype_eng.util.ExternalEnumValues;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;


/**
 * An exact rotation of structure offsets and directions. {@link BlockOrientation#NORTH_UP} is the
 * base orientation: positive X is right, positive Y is top, and negative Z is front. {@code apply}
 * rotates offsets; {@link #applyAt(BlockPos, BlockPos)} also adds the world anchor.
 */
public final class StructureTransform {
    private static final StructureTransform[] ROTATIONS = createRotations();

    private final EnumFacing east;
    private final EnumFacing up;
    private final EnumFacing south;
    private final int inverseOrdinal;

    private StructureTransform(BlockOrientation orientation) {
        east = orientation.rotate(EnumFacing.EAST);
        up = orientation.rotate(EnumFacing.UP);
        south = orientation.rotate(EnumFacing.SOUTH);
        inverseOrdinal = BlockOrientation.get(
            orientation.resultingRotate(EnumFacing.NORTH),
            orientation.resultingRotate(EnumFacing.UP)).ordinal();
    }

    private static StructureTransform[] createRotations() {
        BlockOrientation[] orientations = ExternalEnumValues.BLOCK_ORIENTATIONS;
        StructureTransform[] rotations = new StructureTransform[orientations.length];
        for (BlockOrientation orientation : orientations) {
            rotations[orientation.ordinal()] = new StructureTransform(orientation);
        }
        return rotations;
    }

    /**
     * Rotates offsets expressed in NORTH_UP into the target orientation.
     */
    public static StructureTransform of(BlockOrientation orientation) {
        return ROTATIONS[orientation.ordinal()];
    }

    /**
     * Rotates offsets already expressed in {@code from} into {@code to}.
     */
    public static StructureTransform between(BlockOrientation from, BlockOrientation to) {
        return of(BlockOrientation.get(
            to.rotate(from.resultingRotate(EnumFacing.NORTH)),
            to.rotate(from.resultingRotate(EnumFacing.UP))));
    }

    /**
     * Returns an immutable rotated offset, even when the input is mutable.
     *
     * @throws ArithmeticException if a rotated coordinate cannot be represented as an int
     */
    public BlockPos apply(BlockPos position) {
        return apply(position.getX(), position.getY(), position.getZ(), BlockPos.ORIGIN);
    }

    /**
     * Rotates a packed position without allocating an intermediate decoded BlockPos.
     */
    public BlockPos apply(long position) {
        return apply(BlockPos2ObjectMap.unpackX(position), BlockPos2ObjectMap.unpackY(position), BlockPos2ObjectMap.unpackZ(position), BlockPos.ORIGIN);
    }

    /**
     * Rotates and translates in one allocation, retaining exact intermediate coordinate checks.
     */
    public BlockPos applyAt(BlockPos origin, BlockPos offset) {
        return apply(offset.getX(), offset.getY(), offset.getZ(), origin);
    }

    private BlockPos apply(long x, long y, long z, BlockPos origin) {
        return new BlockPos(
            Math.addExact(origin.getX(), Math.toIntExact(x * east.getXOffset() + y * up.getXOffset() + z * south.getXOffset())),
            Math.addExact(origin.getY(), Math.toIntExact(x * east.getYOffset() + y * up.getYOffset() + z * south.getYOffset())),
            Math.addExact(origin.getZ(), Math.toIntExact(x * east.getZOffset() + y * up.getZOffset() + z * south.getZOffset())));
    }

    /**
     * Rotates a direction with the same signed permutation used for coordinates.
     */
    public EnumFacing apply(EnumFacing direction) {
        return switch (direction) {
            case EAST -> east;
            case WEST -> east.getOpposite();
            case UP -> up;
            case DOWN -> up.getOpposite();
            case SOUTH -> south;
            case NORTH -> south.getOpposite();
        };
    }

    public StructureTransform inverse() {
        return ROTATIONS[inverseOrdinal];
    }
}
