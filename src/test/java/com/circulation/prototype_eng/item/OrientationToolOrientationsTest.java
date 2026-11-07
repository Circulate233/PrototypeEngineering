package com.circulation.prototype_eng.item;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                current = OrientationToolOrientations.apply(current, front, false);
            }
            assertEquals(OrientationToolOrientations.canonical(front), current);
        }
    }

    @Test
    void sneakCyclesAllTwentyFourWithTheOriginalFacingOrderFromEveryStartingOrientation() {
        for (var start : BlockOrientation.values()) {
            var seen = EnumSet.noneOf(BlockOrientation.class);
            var current = start;
            for (int step = 0; step < 24; step++) {
                assertTrue(seen.add(current), start + " step " + step);
                var front = current.getSide(RelativeSide.FRONT);
                var top = current.getSide(RelativeSide.TOP);
                int twist = 0;
                while (PM_TOPS[front.ordinal()][twist] != top) { twist++; }
                var nextFront = twist == 3 ? EnumFacing.VALUES[(front.ordinal() + 1) % 6] : front;
                var nextTop = PM_TOPS[nextFront.ordinal()][(twist + 1) % 4];
                current = OrientationToolOrientations.apply(current, EnumFacing.DOWN, true);
                assertEquals(BlockOrientation.get(nextFront, nextTop), current);
            }
            assertEquals(24, seen.size());
            assertEquals(start, current);
        }
    }

    @Test
    void otherFacesResetToThePmCanonicalTopIncludingVerticalFacing() {
        for (var current : BlockOrientation.values()) {
            for (var clicked : EnumFacing.VALUES) {
                if (clicked == current.getSide(RelativeSide.FRONT)) { continue; }
                assertEquals(BlockOrientation.get(clicked, PM_TOPS[clicked.ordinal()][0]),
                    OrientationToolOrientations.apply(current, clicked, false));
            }
        }
    }

    @Test
    void relativeRequestsComposeAgainstTheResultOfEarlierRequests() {
        UnaryOperator<BlockOrientation> eastClick = current -> OrientationToolOrientations.apply(current, EnumFacing.EAST, false);
        var current = eastClick.apply(BlockOrientation.NORTH_UP);
        current = eastClick.apply(current);
        assertEquals(BlockOrientation.EAST_SOUTH, current);
        current = OrientationToolOrientations.apply(current, EnumFacing.UP, true);
        assertEquals(BlockOrientation.EAST_DOWN, current);
    }
}
