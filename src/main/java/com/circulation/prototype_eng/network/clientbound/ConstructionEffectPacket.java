package com.circulation.prototype_eng.network.clientbound;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.network.ConnectionClientboundPacket;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload;
import io.netty.buffer.ByteBuf;

public final class ConstructionEffectPacket extends ConnectionClientboundPacket {
    private ConstructionEffectPayload payload;

    public ConstructionEffectPacket() {
    }

    public ConstructionEffectPacket(ConstructionEffectPayload payload) {
        this.payload = payload;
    }

    @Override
    protected void read(ByteBuf buffer) {
        payload = ConstructionEffectPayload.read(buffer);
    }

    @Override
    protected void write(ByteBuf buffer) {
        ConstructionEffectPayload.write(buffer, payload);
    }

    @Override
    protected void handleClientConnection(Object connection) {
        PrototypeEngineering.proxy.receiveConstructionEffect(payload, connection);
    }
}
