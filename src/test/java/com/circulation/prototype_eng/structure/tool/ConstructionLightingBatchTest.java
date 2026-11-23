package com.circulation.prototype_eng.structure.tool;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionLightingBatchTest {
    @Test
    void placementAndRollbackShareOneCheckButDifferentWorldsDoNot() {
        var clock = new AtomicLong();
        var checks = new ArrayList<Check>();
        var batch = new ConstructionLightingBatch<String>((world, pos) -> checks.add(new Check(world, pos)), clock::get);
        var pos = new BlockPos(-16, 71, 33);
        var cursor = new BlockPos.MutableBlockPos(pos);

        batch.add("overworld", cursor);
        long reserved = batch.reservedNanos();
        cursor.setPos(pos.getX() + 1, pos.getY(), pos.getZ());
        batch.add("overworld", pos);
        assertEquals(reserved, batch.reservedNanos());
        batch.add("nether", pos);

        assertTrue(checks.isEmpty());
        batch.flush(() -> true);
        var overworld = new Check("overworld", pos);
        var nether = new Check("nether", pos);
        assertEquals(2, checks.size());
        assertEquals(Set.of(overworld, nether), Set.copyOf(checks));

        batch.add("overworld", pos);
        batch.flush(() -> true);
        assertEquals(overworld, checks.getLast());
        assertEquals(3, checks.size());
    }

    @Test
    void deadlineDefersUncheckedPositionsAndResumingDoesNotRepeatCompletedChecks() {
        var clock = new AtomicLong();
        var checks = new ArrayList<BlockPos>();
        var batch = new ConstructionLightingBatch<String>((_, pos) -> {
            checks.add(pos);
            clock.incrementAndGet();
        }, clock::get);
        var positions = List.of(new BlockPos(24, 62, -57), new BlockPos(25, 62, -57), new BlockPos(24, 63, -57));
        for (var pos : positions) batch.add("overworld", pos);

        batch.flush(() -> false);
        assertTrue(checks.isEmpty());
        long deadline = clock.get() + 1;
        batch.flush(() -> clock.get() < deadline);
        assertEquals(1, checks.size());

        batch.flush(() -> true);
        assertEquals(positions.size(), checks.size());
        assertEquals(Set.copyOf(positions), Set.copyOf(checks));
        batch.flush(() -> true);
        assertEquals(positions.size(), checks.size());
    }

    @Test
    void unloadingOneWorldDiscardsOnlyItsUncheckedPositions() {
        var clock = new AtomicLong();
        var checks = new ArrayList<Check>();
        var batch = new ConstructionLightingBatch<String>((world, pos) -> checks.add(new Check(world, pos)), clock::get);
        var pos = new BlockPos(4, 85, 8);
        batch.add("overworld", pos);
        batch.add("nether", pos);
        batch.clear("nether");

        batch.flush(() -> true);
        assertEquals(List.of(new Check("overworld", pos)), checks);
    }

    private record Check(String world, BlockPos pos) {
    }
}
