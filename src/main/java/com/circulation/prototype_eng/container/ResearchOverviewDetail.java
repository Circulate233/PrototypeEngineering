package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.network.ResearchPayload;
import net.minecraft.network.PacketBuffer;

public record ResearchOverviewDetail(ResearchOverviewPacket.Definition definition, String json) {
    public static ResearchOverviewDetail read(PacketBuffer data) {
        return new ResearchOverviewDetail(ResearchOverviewPacket.readDefinition(data), ResearchPayload.readText(data));
    }

    public void write(PacketBuffer data) {
        ResearchOverviewPacket.writeDefinition(data, definition);
        ResearchPayload.writeText(data, json);
    }
}
