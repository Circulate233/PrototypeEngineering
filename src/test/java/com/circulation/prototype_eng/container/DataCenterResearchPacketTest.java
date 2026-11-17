package com.circulation.prototype_eng.container;

import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DataCenterResearchPacketTest {
    @Test
    void roundTripPreservesResearchIdentityAndDisplayMetadata() {
        var packet = new DataCenterResearchPacket(List.of(
            new DataCenterResearchPacket.Entry(new ResourceLocation("test", "parallel"), 2, 16, 3),
            new DataCenterResearchPacket.Entry(new ResourceLocation("test", "storage"), 300, 1000, 4)));
        var buffer = Unpooled.buffer();
        try {
            packet.writeToPacket(buffer);
            DataCenterResearchPacket.EMPTY.writeToPacket(buffer);
            assertEquals(packet, new DataCenterResearchPacket(buffer));
            assertEquals(DataCenterResearchPacket.EMPTY, new DataCenterResearchPacket(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
