package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.util.ExternalEnumValues;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureTransformTest {
    /**
     * Independent integer basis: right = front cross top; positive Z points away from front.
     */
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

    @Test
    void everyOrientationUsesItsFrontTopAndRightHandedAxesForPositionsAndDirections() {
        BlockPos offset = new BlockPos(7, -11, 19);
        for (BlockOrientation orientation : ExternalEnumValues.BLOCK_ORIENTATIONS) {
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
        for (BlockOrientation from : ExternalEnumValues.BLOCK_ORIENTATIONS) {
            StructureTransform source = StructureTransform.of(from);
            for (BlockOrientation to : ExternalEnumValues.BLOCK_ORIENTATIONS) {
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

}
