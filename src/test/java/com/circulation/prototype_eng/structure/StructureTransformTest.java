package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructureTransformTest {
    @Test
    void yawAndRollPreserveThePmNorthUpConvention() {
        BlockPos offset = new BlockPos(2, 3, -5);
        StructureTransform east = StructureTransform.of(BlockOrientation.EAST_UP);
        assertEquals(new BlockPos(5, 3, 2), east.apply(offset));
        assertEquals(EnumFacing.EAST, east.apply(EnumFacing.NORTH));
        assertEquals(EnumFacing.SOUTH, east.apply(EnumFacing.EAST));
        assertEquals(EnumFacing.WEST, east.apply(EnumFacing.SOUTH));
        assertEquals(EnumFacing.NORTH, east.apply(EnumFacing.WEST));
        assertEquals(EnumFacing.UP, east.apply(EnumFacing.UP));

        assertEquals(new BlockPos(-3, 2, -5),
                StructureTransform.of(BlockOrientation.NORTH_WEST).apply(offset));
        assertEquals(new BlockPos(-2, 5, -3),
                StructureTransform.of(BlockOrientation.UP_NORTH).apply(offset));
    }

    @Test
    void everyOrientationUsesItsFrontTopAndRightHandedAxesForPositionsAndDirections() {
        BlockPos offset = new BlockPos(7, -11, 19);
        for (BlockOrientation orientation : BlockOrientation.values()) {
            StructureTransform transform = StructureTransform.of(orientation);
            assertEquals(expected(orientation, offset), transform.apply(offset), orientation.name());
            assertEquals(offset, transform.inverse().apply(transform.apply(offset)), orientation.name());
            for (EnumFacing direction : EnumFacing.VALUES) {
                BlockPos unit = new BlockPos(direction.getDirectionVec());
                assertEquals(expected(orientation, unit),
                        new BlockPos(transform.apply(direction).getDirectionVec()),
                        orientation + " / " + direction);
                assertEquals(direction, transform.inverse().apply(transform.apply(direction)),
                        orientation + " / " + direction);
            }
        }
    }

    @Test
    void changingBetweenEveryPairOfOrientationsMatchesTheirBaseTransforms() {
        BlockPos local = new BlockPos(13, -17, 23);
        for (BlockOrientation from : BlockOrientation.values()) {
            StructureTransform source = StructureTransform.of(from);
            for (BlockOrientation to : BlockOrientation.values()) {
                StructureTransform target = StructureTransform.of(to);
                StructureTransform change = StructureTransform.between(from, to);
                String context = from + " -> " + to;
                BlockPos sourceOffset = source.apply(local);
                BlockPos targetOffset = target.apply(local);
                assertEquals(targetOffset, change.apply(sourceOffset), context);
                assertEquals(sourceOffset, change.inverse().apply(targetOffset), context);
                for (EnumFacing direction : EnumFacing.VALUES) {
                    assertEquals(target.apply(direction), change.apply(source.apply(direction)),
                            context + " / " + direction);
                }
            }
        }
    }

    @Test
    void extremeCoordinatesMoveExactlyOrRejectUnrepresentableNegationOnEveryAxis() {
        for (BlockOrientation orientation : BlockOrientation.values()) {
            StructureTransform transform = StructureTransform.of(orientation);
            for (EnumFacing axis : new EnumFacing[] {EnumFacing.EAST, EnumFacing.UP, EnumFacing.SOUTH}) {
                BlockPos maximum = scaled(axis, Integer.MAX_VALUE);
                assertEquals(expected(orientation, maximum), transform.apply(maximum),
                        orientation + " / max " + axis);
                assertEquals(maximum, transform.inverse().apply(transform.apply(maximum)));

                BlockPos minimum = scaled(axis, Integer.MIN_VALUE);
                EnumFacing rotated = transform.apply(axis);
                if (rotated.getAxisDirection() == EnumFacing.AxisDirection.NEGATIVE) {
                    assertThrows(ArithmeticException.class, () -> transform.apply(minimum),
                            orientation + " / min " + axis);
                } else {
                    assertEquals(scaled(rotated, Integer.MIN_VALUE), transform.apply(minimum),
                            orientation + " / min " + axis);
                    assertEquals(minimum, transform.inverse().apply(transform.apply(minimum)));
                }
            }
        }
    }

    @Test
    void evenIdentityCopiesMutablePositionsIntoImmutableResults() {
        BlockPos.MutableBlockPos input = new BlockPos.MutableBlockPos(3, -7, 11);
        BlockPos identity = StructureTransform.of(BlockOrientation.NORTH_UP).apply(input);
        BlockPos rotated = StructureTransform.of(BlockOrientation.EAST_UP).apply(input);
        input.setPos(29, 31, 37);
        assertFalse(identity instanceof BlockPos.MutableBlockPos);
        assertFalse(rotated instanceof BlockPos.MutableBlockPos);
        assertEquals(new BlockPos(3, -7, 11), identity);
        assertEquals(new BlockPos(-11, -7, 3), rotated);
    }

    private static BlockPos scaled(EnumFacing axis, int value) {
        return new BlockPos(axis.getXOffset() * value, axis.getYOffset() * value, axis.getZOffset() * value);
    }

    /** Independent integer basis: right = front cross top; positive Z points away from front. */
    private static BlockPos expected(BlockOrientation orientation, BlockPos position) {
        EnumFacing front = orientation.getSide(RelativeSide.FRONT);
        EnumFacing top = orientation.getSide(RelativeSide.TOP);
        int rightX = front.getYOffset() * top.getZOffset() - front.getZOffset() * top.getYOffset();
        int rightY = front.getZOffset() * top.getXOffset() - front.getXOffset() * top.getZOffset();
        int rightZ = front.getXOffset() * top.getYOffset() - front.getYOffset() * top.getXOffset();
        long x = position.getX();
        long y = position.getY();
        long z = position.getZ();
        return new BlockPos(
                Math.toIntExact(x * rightX + y * top.getXOffset() - z * front.getXOffset()),
                Math.toIntExact(x * rightY + y * top.getYOffset() - z * front.getYOffset()),
                Math.toIntExact(x * rightZ + y * top.getZOffset() - z * front.getZOffset()));
    }
}
