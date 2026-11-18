package com.circulation.prototype_eng.network.clientbound;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.network.ConnectionClientboundPacket;
import com.circulation.prototype_eng.network.ResearchPayload;
import io.netty.buffer.ByteBuf;

public final class ResearchViewPacket extends ConnectionClientboundPacket {
    public static final int CATALOG = 0;
    public static final int EDITOR = 1;
    public static final int DETAIL = 2;
    public static final int OVERVIEW = 3;
    public static final int EDIT_RESULT = 4;

    private int kind;
    private int window;
    private ResearchPayload.Part part;

    public ResearchViewPacket() {
    }

    public ResearchViewPacket(int kind, int window, ResearchPayload.Part part) {
        this.kind = kind;
        this.window = window;
        this.part = part;
    }

    public int kind() {
        return kind;
    }

    public int window() {
        return window;
    }

    public ResearchPayload.Part part() {
        return part;
    }

    @Override
    protected void read(ByteBuf buffer) {
        kind = buffer.readUnsignedByte();
        window = buffer.readUnsignedByte();
        if (kind > EDIT_RESULT || (kind == CATALOG) != (window == 0)) throw new IllegalArgumentException("Invalid research view packet");
        part = ResearchPayload.Part.read(buffer);
    }

    @Override
    protected void write(ByteBuf buffer) {
        buffer.writeByte(kind);
        buffer.writeByte(window);
        part.write(buffer);
    }

    @Override
    protected void handleClientConnection(Object connection) {
        PrototypeEngineering.proxy.receiveResearchView(this, connection);
    }
}
