package com.circulation.prototype_eng.structure.tool.effect;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            var initial = new ConstructionEffectPayload.Geometry(-1, 1, position.toLong(), false, 90, 98,
                section, quadrant, true, cells, new BitSet(), new BitSet());
            var delta = new ConstructionEffectPayload.Geometry(-1, 1, position.toLong(), false, 90, 99,
                section, quadrant, false, new BitSet(), cells, new BitSet());
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
                section, quadrant, true, subset, subset, new BitSet());
            assertEquals(payload, roundTrip(payload));
            encoded += ConstructionEffectPayload.bytes(payload);
        }
        assertEquals(25 * 16 * 15, cells);
        assertTrue(encoded < cells * Long.BYTES);
    }

    @Test
    void excludedPositionsAreSeparateFromActualCommitsAndOutgoingBuffersAreIndependent() {
        var position = new BlockPos(-41, 40, 93);
        var completed = new BitSet();
        var excluded = new BitSet();
        int cell = ConstructionEffectPayload.Section.index(position);
        completed.set(cell);
        excluded.set(cell ^ 1);
        var section = ConstructionEffectPayload.Section.at(position);
        int quadrant = ConstructionEffectPayload.Section.quadrant(position);
        assertThrows(IllegalArgumentException.class, () -> new ConstructionEffectPayload.Geometry(0, 9, position.toLong(),
            false, 40, 50, section, quadrant, false, new BitSet(), completed, completed));
        var payload = new ConstructionEffectPayload.Geometry(0, 9, position.toLong(), false, 40, 50,
            section, quadrant, false, new BitSet(), completed, excluded);
        completed.clear();
        excluded.clear();
        var received = (ConstructionEffectPayload.Geometry) roundTrip(payload);
        assertTrue(received.committed().get(cell));
        assertTrue(received.excluded().get(cell ^ 1));
    }

    @Test
    void statusAndVisibilityMessagesRetainTaskIdentityAndRejectExtraBytes() {
        var anchor = new BlockPos(32, 80, -1);
        var status = new ConstructionEffectPayload.Hud(1, 13, anchor.toLong(), true, 38, 86, 51, 2, 6, true, false);
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
