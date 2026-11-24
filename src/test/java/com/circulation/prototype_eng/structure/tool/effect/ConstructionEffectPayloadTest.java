package com.circulation.prototype_eng.structure.tool.effect;

import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Layout;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionEffectPayloadTest {
    @Test
    void geometryRoundTripsAcrossNegativeChunkBoundaries() {
        for (var position : List.of(new BlockPos(-1, 67, -33), new BlockPos(-17, 67, -17),
            new BlockPos(-32, 67, -32), new BlockPos(-33, 67, -1), new BlockPos(32, 67, 32))) {
            var section = ConstructionEffectPayload.Section.at(position);
            int quadrant = ConstructionEffectPayload.Section.quadrant(position);
            int cell = ConstructionEffectPayload.Section.index(position);
            var cells = new BitSet();
            cells.set(cell);
            var layout = layout(position, false, 90, new ConstructionArea(position, position), 1);
            var initial = new ConstructionEffectPayload.Geometry(-1, 1, 98, section, quadrant, true, layout,
                cells, new BitSet(), new byte[]{0}, new BitSet(), List.of());
            var delta = new ConstructionEffectPayload.Geometry(-1, 1, 99, section, quadrant, false, null, new BitSet(), new BitSet(), new byte[0], new BitSet(),
                List.of(new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(0, false), 98)));
            assertEquals(initial, roundTrip(initial));
            assertEquals(delta, roundTrip(delta));
            var next = new ConstructionEffectPayload.Geometry(-1, 1, 100, section, quadrant, true, null,
                cells, new BitSet(), new byte[]{0}, new BitSet(), delta.regions());
            assertEquals(next, roundTrip(next));
            assertEquals(position.getX() >> 4, section.chunkX(quadrant));
            assertEquals(position.getZ() >> 4, section.chunkZ(quadrant));
            int sectionCell = initial.sectionIndex(cell);
            assertEquals(position, new BlockPos((section.x() << 5) + (sectionCell & 31),
                (section.y() << 5) + (sectionCell >> 10), (section.z() << 5) + ((sectionCell >> 5) & 31)));
        }
    }

    @Test
    void denseSixThousandCellSnapshotUsesLessSpaceThanIndividualPositions() {
        var origin = new BlockPos(35, 70, -32);
        var section = ConstructionEffectPayload.Section.at(origin);
        var area = new ConstructionArea(origin, origin.add(24, 15, 14));
        BitSet[] subsets = {new BitSet(), new BitSet(), new BitSet(), new BitSet()};
        for (int x = 0; x < 25; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 15; z++) {
            var position = origin.add(x, y, z);
            subsets[ConstructionEffectPayload.Section.quadrant(position)].set(ConstructionEffectPayload.Section.index(position));
        }
        int cells = 0;
        int encoded = 0;
        for (int quadrant = 0; quadrant < subsets.length; quadrant++) {
            var subset = subsets[quadrant];
            if (subset.isEmpty()) continue;
            cells += subset.cardinality();
            var payload = new ConstructionEffectPayload.Geometry(0, 7, 260, section, quadrant, true,
                layout(origin, true, 200, area, 25 * 16 * 15), subset, subset,
                new byte[]{0}, new BitSet(),
                List.of(new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(0, true), 259)));
            assertEquals(payload, roundTrip(payload));
            encoded += ConstructionEffectPayload.bytes(payload);
        }
        assertEquals(25 * 16 * 15, cells);
        assertTrue(encoded < cells * Long.BYTES);
    }

    @Test
    void compactChunkIdsAndRegionTimesDistinguishTheSixtyFourthPhase() {
        var position = new BlockPos(-19, 64, 33);
        var section = ConstructionEffectPayload.Section.at(position);
        int quadrant = ConstructionEffectPayload.Section.quadrant(position);
        var cells = new BitSet();
        var fluids = new BitSet();
        cells.set(0);
        cells.set(1);
        cells.set(2);
        fluids.set(1);
        fluids.set(2);
        byte[] ids = {0, 1, 31};
        byte[] groups = ConstructionEffectPayload.packGroups(cells, cell -> ids[cell]);
        var area = new ConstructionArea(position, position.add(2, 0, 0));
        var layout = new Layout(position.toLong(), true, 90, ConstructionEffectPayload.wireTicks(3), 32, area);
        var snapshot = new ConstructionEffectPayload.Geometry(0, 1, 105, section, quadrant, true, layout,
            cells, fluids, groups, new BitSet(), List.of(
                new ConstructionEffectPayload.RegionTime(0, 100),
                new ConstructionEffectPayload.RegionTime(32, 101),
                new ConstructionEffectPayload.RegionTime(63, 102)));

        var received = (ConstructionEffectPayload.Geometry) roundTrip(snapshot);
        assertArrayEquals(ids, received.cellGroups());
        assertEquals(snapshot, received);
    }

    @Test
    void contiguousGroupRunsUseLessSpaceThanInterleavedGroupsWithTheSameCells() {
        var cells = new BitSet();
        cells.set(0, 1000);
        cells.set(6000, 7000);
        var section = new ConstructionEffectPayload.Section(0, 1, 0);
        var grouped = new ConstructionEffectPayload.Geometry(0, 1, 100, section, 0, true, null, cells, new BitSet(),
            ConstructionEffectPayload.packGroups(cells, cell -> cell < 6000 ? 4 : 29), new BitSet(), List.of());
        var interleaved = new ConstructionEffectPayload.Geometry(0, 1, 100, section, 0, true, null, cells, new BitSet(),
            ConstructionEffectPayload.packGroups(cells, cell -> (cell & 1) == 0 ? 4 : 29), new BitSet(), List.of());

        var ids = new byte[cells.cardinality()];
        Arrays.fill(ids, 0, 1000, (byte) 4);
        Arrays.fill(ids, 1000, ids.length, (byte) 29);
        assertArrayEquals(ids, ((ConstructionEffectPayload.Geometry) roundTrip(grouped)).cellGroups());
        assertEquals(interleaved, roundTrip(interleaved));
        assertTrue(ConstructionEffectPayload.bytes(grouped) < ConstructionEffectPayload.bytes(interleaved));
    }

    @Test
    void snapshotKeepsIndependentTransitionTimesForEachSolidAndFluidChunk() {
        var origin = new BlockPos(-19, 64, 33);
        var section = ConstructionEffectPayload.Section.at(origin);
        int quadrant = ConstructionEffectPayload.Section.quadrant(origin);
        var cells = new BitSet();
        var fluids = new BitSet();
        var times = new ArrayList<ConstructionEffectPayload.RegionTime>();
        long start = 90;
        int[] groupIds = new int[ConstructionEffectPayload.CHUNK_CELL_COUNT];
        long tick = start;
        for (int group = 0; group < 4; group++) {
            for (boolean fluid : new boolean[]{false, true}) {
                var position = origin.add(fluid ? 1 : 0, group, 0);
                int cell = ConstructionEffectPayload.Section.index(position);
                cells.set(cell);
                if (fluid) fluids.set(cell);
                groupIds[cell] = group;
                times.add(new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(group, fluid), ++tick));
            }
        }
        var area = new ConstructionArea(origin, origin.add(1, 24, 0));
        var snapshot = new ConstructionEffectPayload.Geometry(0, 1, tick, section, quadrant, true,
            new Layout(origin.toLong(), false, start, ConstructionEffectPayload.wireTicks(cells.cardinality()), 4, area),
            cells, fluids, ConstructionEffectPayload.packGroups(cells, cell -> groupIds[cell]), new BitSet(), times);
        assertEquals(snapshot, roundTrip(snapshot));
    }

    @Test
    void deltaKeepsARegionCompletionTimeAndExcludedPositionsWithoutACommitBitmap() {
        var position = new BlockPos(-41, 40, 93);
        var excluded = new BitSet();
        int cell = ConstructionEffectPayload.Section.index(position);
        excluded.set(cell ^ 1);
        var section = ConstructionEffectPayload.Section.at(position);
        int quadrant = ConstructionEffectPayload.Section.quadrant(position);
        var time = new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(0, false), 45);
        assertThrows(IllegalArgumentException.class, () -> new ConstructionEffectPayload.Geometry(0, 9, 50,
            section, quadrant, false, null, new BitSet(), new BitSet(), new byte[0], excluded, List.of(time, time)));
        var payload = new ConstructionEffectPayload.Geometry(0, 9, 50, section, quadrant, false, null,
            new BitSet(), new BitSet(), new byte[0], excluded, List.of(time));
        excluded.clear();
        var received = (ConstructionEffectPayload.Geometry) roundTrip(payload);
        assertEquals(List.of(time), received.regions());
        assertTrue(received.excluded().get(cell ^ 1));
    }

    @Test
    void statusAndVisibilityMessagesRetainTaskIdentityAndRejectExtraBytes() {
        var anchor = new BlockPos(32, 80, -1);
        var area = new ConstructionArea(anchor, anchor.add(16, 12, 4));
        var status = new ConstructionEffectPayload.Hud(1, 13, layout(anchor, true, 38, area, 59), new Status(51, 2, 6, true));
        var update = new ConstructionEffectPayload.Hud(1, 13, null, new Status(23, 2, 6, true));
        var hide = new ConstructionEffectPayload.Visibility(1, 13, false);
        var show = new ConstructionEffectPayload.Visibility(1, 13, true);
        var end = new ConstructionEffectPayload.End(1, 13, 100, true, false);
        for (var packet : List.of(status, update, hide, show, end)) {
            assertEquals(packet, roundTrip(packet));
            ByteBuf buffer = Unpooled.buffer();
            try {
                ConstructionEffectPayload.write(buffer, packet);
                buffer.writeByte(0);
                assertThrows(IllegalArgumentException.class, () -> ConstructionEffectPayload.read(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void partialGeometryAndOwnerStatusCarryTheFullRangeBeyondTheirOwnChunk() {
        var anchor = new BlockPos(-79, 64, -8);
        var watchedCell = anchor.add(1, 0, 0);
        var extent = new ConstructionArea.Builder();
        extent.include(watchedCell);
        extent.include(new BlockPos(17, 80, 12));
        extent.include(new BlockPos(-82, 79, 10));
        var area = Objects.requireNonNull(extent.build());
        assertEquals(new BlockPos(-82, 64, -8), area.min());
        assertEquals(new BlockPos(17, 80, 12), area.max());

        var cells = new BitSet();
        cells.set(ConstructionEffectPayload.Section.index(watchedCell));
        var layout = layout(anchor, false, 10, area, 1);
        var snapshot = new ConstructionEffectPayload.Geometry(0, 1, 13,
            ConstructionEffectPayload.Section.at(watchedCell), ConstructionEffectPayload.Section.quadrant(watchedCell), true,
            layout, cells, new BitSet(), new byte[]{0}, new BitSet(), List.of());
        var status = new ConstructionEffectPayload.Hud(0, 1, layout, new Status(1, 0, 0, false));
        var observer = (ConstructionEffectPayload.Geometry) roundTrip(snapshot);
        var owner = (ConstructionEffectPayload.Hud) roundTrip(status);
        var observerArea = Objects.requireNonNull(observer.layout()).area();
        var ownerArea = Objects.requireNonNull(owner.layout()).area();
        assertEquals(area, observerArea);
        assertEquals(area, ownerArea);
        assertEquals(cells, observer.cells());
        for (var received : List.of(observerArea, ownerArea)) {
            assertTrue(received.withinRenderDistance(48, 75, 4));
            assertFalse(received.withinRenderDistance(51, 75, 4));
        }
    }

    private static Layout layout(BlockPos anchor, boolean dismantle, long start, ConstructionArea area, int cells) {
        return new Layout(anchor.toLong(), dismantle, start, ConstructionEffectPayload.wireTicks(cells), 1, area);
    }

    @Test
    void distanceUsesTheOuterBlockFacesAndTheNearestPointInThreeDimensions() {
        var area = new ConstructionArea(new BlockPos(-8, 63, -12), new BlockPos(17, 66, 11));
        assertTrue(area.withinRenderDistance(18, 64, 0));
        assertTrue(area.withinRenderDistance(50, 64, 0));
        assertFalse(area.withinRenderDistance(50.01, 64, 0));
        assertTrue(area.withinRenderDistance(-40, 64, 0));
        assertFalse(area.withinRenderDistance(-40.01, 64, 0));
        assertTrue(area.withinRenderDistance(0, 99, 0));
        assertFalse(area.withinRenderDistance(0, 99.01, 0));
        assertTrue(area.withinRenderDistance(38, 64, 32));
        assertFalse(area.withinRenderDistance(42, 64, 36));
    }

    private static ConstructionEffectPayload roundTrip(ConstructionEffectPayload payload) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ConstructionEffectPayload.write(buffer, payload);
            assertEquals(ConstructionEffectPayload.bytes(payload), buffer.readableBytes());
            assertTrue(buffer.readableBytes() <= ConstructionEffectPayload.MAX_BYTES);
            return ConstructionEffectPayload.read(buffer);
        } finally {
            buffer.release();
        }
    }
}
