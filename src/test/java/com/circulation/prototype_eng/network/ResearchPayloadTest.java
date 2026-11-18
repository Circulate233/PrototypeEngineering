package com.circulation.prototype_eng.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchPayloadTest {
    @Test
    void assemblesLongMultibyteTextOnlyAfterReceivingAllParts() {
        var text = "研究记录：解锁更多材料与设备。\n".repeat(ResearchPayload.PART_BYTES / 4);
        var parts = packets(text);
        assertTrue(parts.size() > 2);
        var assembly = new ResearchPayload.Assembly();
        var received = new ArrayList<String>();
        for (int i = 0; i < parts.size(); i++) {
            var buffer = Unpooled.buffer();
            try {
                parts.get(i).write(buffer);
                var decoded = ResearchPayload.Part.read(buffer);
                assembly.accept(decoded, value -> received.add(ResearchPayload.readText(new PacketBuffer(value))));
                if (i + 1 < parts.size()) assertTrue(received.isEmpty());
            } finally {
                buffer.release();
            }
        }
        assertEquals(List.of(text), received);
    }

    @Test
    void rejectsPartsFromAnotherTransferAndAcceptsTheNextCompleteTransfer() {
        var first = packets("甲".repeat(ResearchPayload.PART_BYTES));
        var second = packets("乙".repeat(ResearchPayload.PART_BYTES));
        var assembly = new ResearchPayload.Assembly();
        var received = new ArrayList<String>();
        assembly.accept(first.getFirst(), buffer -> received.add(ResearchPayload.readText(new PacketBuffer(buffer))));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(second.get(1), _ -> received.add("unexpected")));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(first.get(1), _ -> received.add("unexpected")));
        assertTrue(received.isEmpty());

        var replacement = "下一份研究详情";
        var complete = packets(replacement);
        for (var part : complete) assembly.accept(part, buffer -> received.add(ResearchPayload.readText(new PacketBuffer(buffer))));
        assertEquals(List.of(replacement), received);
    }

    private static List<ResearchPayload.Part> packets(String text) {
        var encoded = ResearchPayload.encode(buffer -> ResearchPayload.writeText(new PacketBuffer(buffer), text));
        var parts = new ArrayList<ResearchPayload.Part>();
        ResearchPayload.split(encoded, parts::add);
        return parts;
    }
}
