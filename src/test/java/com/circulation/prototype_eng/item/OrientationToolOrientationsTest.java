package com.circulation.prototype_eng.item;

import com.circulation.prototype_eng.util.ExternalEnumValues;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.machine.ControllerOrientation;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static com.circulation.prototype_eng.item.OrientationToolOrientations.Part.BOTTOM;
import static com.circulation.prototype_eng.item.OrientationToolOrientations.Part.CENTER;
import static com.circulation.prototype_eng.item.OrientationToolOrientations.Part.CORNER;
import static com.circulation.prototype_eng.item.OrientationToolOrientations.Part.LEFT;
import static com.circulation.prototype_eng.item.OrientationToolOrientations.Part.RIGHT;
import static com.circulation.prototype_eng.item.OrientationToolOrientations.Part.TOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OrientationToolOrientationsTest {
    private static final EnumFacing[][] PM_TOPS = {
        {EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.NORTH, EnumFacing.EAST},
        {EnumFacing.NORTH, EnumFacing.WEST, EnumFacing.SOUTH, EnumFacing.EAST},
        {EnumFacing.UP, EnumFacing.EAST, EnumFacing.DOWN, EnumFacing.WEST},
        {EnumFacing.UP, EnumFacing.WEST, EnumFacing.DOWN, EnumFacing.EAST},
        {EnumFacing.UP, EnumFacing.NORTH, EnumFacing.DOWN, EnumFacing.SOUTH},
        {EnumFacing.UP, EnumFacing.SOUTH, EnumFacing.DOWN, EnumFacing.NORTH}
    };

    @Test
    void frontClicksKeepAllSixFrontsAndFollowEveryOriginalClockwiseTwist() {
        for (var front : EnumFacing.VALUES) {
            var current = OrientationToolOrientations.canonical(front);
            for (int twist = 0; twist < 4; twist++) {
                assertEquals(front, current.getSide(RelativeSide.FRONT));
                assertEquals(PM_TOPS[front.ordinal()][twist], current.getSide(RelativeSide.TOP));
                current = OrientationToolOrientations.apply(current, front, CENTER);
            }
            assertEquals(OrientationToolOrientations.canonical(front), current);
        }
    }

    @Test
    void everyVisibleEdgeTargetsItsAdjacentFaceAndAllFourCornersDoNothing() {
        for (var current : ExternalEnumValues.BLOCK_ORIENTATIONS) {
            for (var face : EnumFacing.VALUES) {
                for (var part : OrientationToolOrientations.Part.VALUES) {
                    if (part == CORNER) continue;
                    double u = part == LEFT ? .1 : part == RIGHT ? .9 : .5;
                    double v = part == BOTTOM ? .1 : part == TOP ? .9 : .5;
                    var point = OrientationToolOrientations.point(face, u, v);
                    var clicked = OrientationToolOrientations.part(face, point.x, point.y, point.z);
                    assertEquals(part, clicked, face + " " + part);
                    var result = OrientationToolOrientations.apply(current, face, clicked);
                    assertEquals(OrientationToolOrientations.target(face, part), result.getSide(RelativeSide.FRONT));
                }
                for (double u : new double[]{.1, .9}) {
                    for (double v : new double[]{.1, .9}) {
                        var corner = OrientationToolOrientations.point(face, u, v);
                        var part = OrientationToolOrientations.part(face, corner.x, corner.y, corner.z);
                        assertSame(CORNER, part);
                        assertSame(current, OrientationToolOrientations.apply(current, face, part));
                    }
                }
            }
        }
    }

    @Test
    void aligningAnotherFaceAndTurningBackPreservesEveryOriginalTwist() {
        for (var current : ExternalEnumValues.BLOCK_ORIENTATIONS) {
            for (var clicked : EnumFacing.VALUES) {
                if (clicked == current.getSide(RelativeSide.FRONT)) {
                    continue;
                }
                var turned = OrientationToolOrientations.apply(current, clicked, CENTER);
                assertEquals(clicked, turned.getSide(RelativeSide.FRONT));
                assertEquals(current, OrientationToolOrientations.apply(turned, current.getSide(RelativeSide.FRONT), CENTER));
            }
        }
    }

    @Test
    void relativeRequestsComposeAgainstTheResultOfEarlierRequests() {
        UnaryOperator<BlockOrientation> eastClick = current -> OrientationToolOrientations.apply(current, EnumFacing.EAST, CENTER);
        var current = eastClick.apply(BlockOrientation.NORTH_UP);
        current = eastClick.apply(current);
        assertEquals(BlockOrientation.EAST_SOUTH, current);
        current = OrientationToolOrientations.apply(current, EnumFacing.EAST, TOP);
        assertEquals(EnumFacing.UP, current.getSide(RelativeSide.FRONT));
        assertEquals(EnumFacing.SOUTH, current.getSide(RelativeSide.TOP));
    }

    @Test
    void placementTwistTracksTheCameraOnBothVerticalFacesAndObliqueHorizontalViews() {
        assertEquals(BlockOrientation.UP_SOUTH, ControllerOrientation.placement(EnumFacing.UP, 0, 90));
        assertEquals(BlockOrientation.UP_WEST, ControllerOrientation.placement(EnumFacing.UP, 90, 90));
        assertEquals(BlockOrientation.UP_NORTH, ControllerOrientation.placement(EnumFacing.UP, 180, 90));
        assertEquals(BlockOrientation.UP_EAST, ControllerOrientation.placement(EnumFacing.UP, 270, 90));
        assertEquals(BlockOrientation.DOWN_NORTH, ControllerOrientation.placement(EnumFacing.DOWN, 0, -90));
        assertEquals(BlockOrientation.DOWN_EAST, ControllerOrientation.placement(EnumFacing.DOWN, 90, -90));
        assertEquals(BlockOrientation.NORTH_UP, ControllerOrientation.placement(EnumFacing.NORTH, 0, 0));
        assertEquals(BlockOrientation.NORTH_WEST, ControllerOrientation.placement(EnumFacing.NORTH, 90, 80));
        assertEquals(BlockOrientation.NORTH_EAST, ControllerOrientation.placement(EnumFacing.NORTH, 90, -80));
        assertSame(EnumFacing.WEST, ControllerOrientation.placement(EnumFacing.UP, 90, 0).getSide(RelativeSide.TOP));
    }
}
