package com.circulation.prototype_eng.structure.tool.effect;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionFadePlanTest {
    @Test
    void smallRepairsAcrossTheWholeLayoutShareOneFade() {
        int[] counts = new int[ConstructionChunkPlan.MAX_CHUNKS];
        counts[0] = 80;
        counts[counts.length / 2] = 180;
        counts[counts.length - 1] = 40;
        var plan = ConstructionFadePlan.create(counts);

        assertEquals(1, plan.size());
        assertArrayEquals(new int[counts.length], groupIds(plan, counts.length));
    }

    @Test
    void segmentBoundariesFollowCumulativeWorkAcrossUnevenPhysicalGroups() {
        int[] counts = {1_300, 1_200, 1_900, 1_000, 600, 1_550, 1_450};
        var plan = ConstructionFadePlan.create(counts);

        assertEquals(3, plan.size());
        assertArrayEquals(new int[]{0, 0, 1, 1, 1, 2, 2}, groupIds(plan, counts.length));
    }

    @Test
    void aLargeRepairInOnePhysicalGroupDoesNotCreateEmptyStages() {
        var one = ConstructionFadePlan.create(new int[]{0, 18_000, 0});
        assertEquals(1, one.size());

        int[] counts = {0, 1, 0, 18_000, 0, 1, 0};
        var split = ConstructionFadePlan.create(counts);
        assertEquals(3, split.size());
        assertArrayEquals(new int[]{0, 1, 2}, IntStream.of(1, 3, 5).map(split::group).toArray());
    }

    private static int[] groupIds(ConstructionFadePlan plan, int physicalGroups) {
        return IntStream.range(0, physicalGroups).map(plan::group).toArray();
    }
}
