package com.circulation.prototype_eng.structure.tool.effect;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

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
            var initial = new ConstructionEffectPayload.Geometry(-1, 1, position.toLong(), false, 90, 98, ConstructionEffectPayload.wireTicks(1),
                section, quadrant, new ConstructionArea(position, position), cells, new BitSet(), new BitSet(), List.of());
            var delta = new ConstructionEffectPayload.Geometry(-1, 1, position.toLong(), false, 90, 99, initial.wireTicks(),
                section, quadrant, null, new BitSet(), new BitSet(), new BitSet(),
                List.of(new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(cell, false), -1, 98)));
            assertEquals(initial, roundTrip(initial));
            assertEquals(delta, roundTrip(delta));
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
            var payload = new ConstructionEffectPayload.Geometry(0, 7, origin.toLong(), true, 200, 260,
                ConstructionEffectPayload.wireTicks(25 * 16 * 15), section, quadrant, area, subset, subset, new BitSet(),
                List.of(new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(subset.nextSetBit(0), true), 251, 259)));
            assertEquals(payload, roundTrip(payload));
            encoded += ConstructionEffectPayload.bytes(payload);
        }
        assertEquals(25 * 16 * 15, cells);
        assertTrue(encoded < cells * Long.BYTES);
    }

    @Test
    void snapshotKeepsIndependentTransitionTimesForEachSolidAndFluidLayer() {
        var origin = new BlockPos(-19, 64, 33);
        var section = ConstructionEffectPayload.Section.at(origin);
        int quadrant = ConstructionEffectPayload.Section.quadrant(origin);
        var cells = new BitSet();
        var fluids = new BitSet();
        var times = new ArrayList<ConstructionEffectPayload.RegionTime>();
        long start = 90;
        long tick = start;
        for (boolean fluid : new boolean[]{false, true}) {
            for (int layer = 0; layer < ConstructionEffectPayload.LAYERS_PER_SECTION; layer++) {
                var position = origin.add(fluid ? 1 : 0, layer * ConstructionEffectPayload.LAYER_HEIGHT, 0);
                int cell = ConstructionEffectPayload.Section.index(position);
                cells.set(cell);
                if (fluid) fluids.set(cell);
                times.add(new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(cell, fluid), -1, ++tick));
            }
        }
        var area = new ConstructionArea(origin, origin.add(1, 24, 0));
        var snapshot = new ConstructionEffectPayload.Geometry(0, 1, origin.toLong(), false, start, tick,
            ConstructionEffectPayload.wireTicks(cells.cardinality()), section, quadrant, area, cells, fluids, new BitSet(), times);
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
        var time = new ConstructionEffectPayload.RegionTime(ConstructionEffectPayload.region(cell, false), -1, 45);
        int wireTicks = ConstructionEffectPayload.wireTicks(2);
        assertThrows(IllegalArgumentException.class, () -> new ConstructionEffectPayload.Geometry(0, 9, position.toLong(), false, 40, 50,
            wireTicks, section, quadrant, null, new BitSet(), new BitSet(), excluded, List.of(time, time)));
        var payload = new ConstructionEffectPayload.Geometry(0, 9, position.toLong(), false, 40, 50, wireTicks,
            section, quadrant, null, new BitSet(), new BitSet(), excluded, List.of(time));
        excluded.clear();
        var received = (ConstructionEffectPayload.Geometry) roundTrip(payload);
        assertEquals(List.of(time), received.regions());
        assertTrue(received.excluded().get(cell ^ 1));
    }

    @Test
    void statusAndVisibilityMessagesRetainTaskIdentityAndRejectExtraBytes() {
        var anchor = new BlockPos(32, 80, -1);
        var area = new ConstructionArea(anchor, anchor.add(16, 12, 4));
        var status = new ConstructionEffectPayload.Hud(1, 13, anchor.toLong(), true, 38, 86, area, 51, 2, 6, true, false);
        var unwatch = new ConstructionEffectPayload.Forget(1, 13, ConstructionEffectPayload.Section.at(anchor),
            ConstructionEffectPayload.Section.quadrant(anchor));
        var end = new ConstructionEffectPayload.End(1, 13, 100, true, false);
        for (var packet : List.of(status, unwatch, end)) {
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
        var snapshot = new ConstructionEffectPayload.Geometry(0, 1, anchor.toLong(), false, 10, 13, ConstructionEffectPayload.wireTicks(1),
            ConstructionEffectPayload.Section.at(watchedCell), ConstructionEffectPayload.Section.quadrant(watchedCell),
            area, cells, new BitSet(), new BitSet(), List.of());
        var status = new ConstructionEffectPayload.Hud(0, 1, anchor.toLong(), false, 10, 13, area, 1, 0, 0, false, false);
        var observer = (ConstructionEffectPayload.Geometry) roundTrip(snapshot);
        var owner = (ConstructionEffectPayload.Hud) roundTrip(status);
        var observerArea = Objects.requireNonNull(observer.area());
        var ownerArea = owner.area();
        assertEquals(area, observerArea);
        assertEquals(area, ownerArea);
        assertEquals(cells, observer.cells());
        for (var received : List.of(observerArea, ownerArea)) {
            assertTrue(received.withinRenderDistance(48, 75, 4));
            assertFalse(received.withinRenderDistance(51, 75, 4));
        }
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
