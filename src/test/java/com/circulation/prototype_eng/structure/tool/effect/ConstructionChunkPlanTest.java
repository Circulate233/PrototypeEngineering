package com.circulation.prototype_eng.structure.tool.effect;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionChunkPlanTest {
    @Test
    void emptyPlacementPlanContainsNoAnimationChunks() {
        assertEquals(0, plan(new LongArrayList(), false).size());
    }

    @Test
    void connectedRowsSplitIntoConnectedAnimationChunks() {
        var positions = row(ConstructionChunkPlan.TARGET_BLOCKS * 2 + 1);
        var plan = plan(positions, false);

        assertEquals(3, plan.size());
        for (int chunk = 0; chunk < plan.size(); chunk++) {
            int minimum = Integer.MAX_VALUE;
            int maximum = Integer.MIN_VALUE;
            int count = 0;
            for (int index = 0; index < positions.size(); index++) {
                if (plan.chunkAt(index) != chunk) continue;
                int x = BlockPos.fromLong(positions.getLong(index)).getX();
                minimum = Math.min(minimum, x);
                maximum = Math.max(maximum, x);
                count++;
            }
            assertEquals(maximum - minimum + 1, count);
            assertTrue(count <= ConstructionChunkPlan.TARGET_BLOCKS);
        }
    }

    @Test
    void largerStructuresIncreaseChunkCapacityAndStayWithinTheThirtyTwoChunkLimit() {
        int blocks = ConstructionChunkPlan.TARGET_BLOCKS * ConstructionChunkPlan.MAX_CHUNKS + 1;
        var positions = row(blocks);
        var plan = plan(positions, false);

        assertEquals(ConstructionChunkPlan.MAX_CHUNKS, plan.size());
        int[] counts = new int[plan.size()];
        for (int index = 0; index < positions.size(); index++) counts[plan.chunkAt(index)]++;
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int count : counts) {
            minimum = Math.min(minimum, count);
            maximum = Math.max(maximum, count);
        }
        assertTrue(maximum > ConstructionChunkPlan.TARGET_BLOCKS);
        assertTrue(maximum - minimum <= 1);
    }

    @Test
    void eachChunkInAConnectedStructureIsFaceConnected() {
        int width = 30, depth = 25, height = 3;
        var positions = new LongArrayList(width * depth * height);
        for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++)
            positions.add(new BlockPos(x, y + 64, z).toLong());
        var plan = plan(positions, false);
        assertTrue(plan.size() >= (positions.size() + ConstructionChunkPlan.TARGET_BLOCKS - 1) / ConstructionChunkPlan.TARGET_BLOCKS);
        assertTrue(plan.size() <= ConstructionChunkPlan.MAX_CHUNKS);

        for (int chunk = 0; chunk < plan.size(); chunk++) {
            var cells = new HashSet<BlockPos>();
            for (int index = 0; index < positions.size(); index++)
                if (plan.chunkAt(index) == chunk) cells.add(BlockPos.fromLong(positions.getLong(index)));
            var visited = new HashSet<BlockPos>();
            var pending = new ArrayDeque<BlockPos>();
            pending.add(cells.iterator().next());
            while (!pending.isEmpty()) {
                var current = pending.removeFirst();
                if (!visited.add(current)) continue;
                for (var facing : EnumFacing.values()) {
                    var neighbor = current.offset(facing);
                    if (cells.contains(neighbor) && !visited.contains(neighbor)) pending.addLast(neighbor);
                }
            }
            assertTrue(!cells.isEmpty() && cells.size() <= ConstructionChunkPlan.TARGET_BLOCKS);
            assertEquals(cells.size(), visited.size());
        }
    }

    @Test
    void moreThanThirtyTwoDetachedPartsShareGroupsWithoutAnOversizedLastGroup() {
        var positions = new LongArrayList();
        for (int x = 0; x < 450; x++) for (int z = 0; z < 40; z++)
            positions.add(new BlockPos(-800 + x * 2, 64, -64 + z * 2).toLong());
        var plan = plan(positions, false);
        int minimumCapacity = positions.size() / ConstructionChunkPlan.MAX_CHUNKS;
        int[] counts = new int[plan.size()];
        for (int i = 0; i < positions.size(); i++) counts[plan.chunkAt(i)]++;
        for (int count : counts) assertTrue(count >= minimumCapacity && count <= minimumCapacity + 1);
    }

    @Test
    void groupOrderPreservesVerticalNeighborsForPlacementAndRemoval() {
        for (boolean dismantle : new boolean[]{false, true}) {
            int width = 29, depth = 23, height = 4;
            int layer = width * depth;
            var positions = new LongArrayList();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++)
                positions.add(new BlockPos(x - 20, 70 + (dismantle ? -y : y), z - 20).toLong());
            var plan = plan(positions, dismantle);
            for (int i = layer; i < positions.size(); i++)
                assertTrue(plan.chunkAt(i - layer) <= plan.chunkAt(i));
        }
    }

    private static ConstructionChunkPlan plan(LongArrayList positions, boolean dismantle) {
        var builder = new ConstructionChunkPlan.Builder(positions, dismantle);
        boolean done;
        do {
            done = builder.step();
        } while (!done);
        return builder.result();
    }

    private static LongArrayList row(int count) {
        var positions = new LongArrayList(count);
        for (int x = 0; x < count; x++) positions.add(new BlockPos(x, 64, 0).toLong());
        return positions;
    }

}
