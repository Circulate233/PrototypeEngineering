package com.circulation.prototype_eng.network.clientbound;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.network.ConnectionClientboundPacket;
import com.circulation.prototype_eng.storage.HatchConfigurationSnapshot;
import io.netty.buffer.ByteBuf;

import java.util.Arrays;
import java.util.UUID;

/**
 * Each 16 KiB message is below vanilla's actual 1 MiB S2C custom-payload limit.
 */
public final class HatchConfigurationPacket extends ConnectionClientboundPacket {
    public static final int CHUNK_BYTES = 16 * 1024;
    private UUID session;
    private long sequence;
    private int totalBytes;
    private int index;
    private byte[] bytes;

    public HatchConfigurationPacket() {
    }

    public HatchConfigurationPacket(UUID session, long sequence, int totalBytes, int index, byte[] bytes) {
        this.session = session;
        this.sequence = sequence;
        this.totalBytes = totalBytes;
        this.index = index;
        this.bytes = bytes;
        validate();
    }

    public static HatchConfigurationPacket part(HatchConfigurationSnapshot snapshot, byte[] payload, int index) {
        return new HatchConfigurationPacket(snapshot.session(), snapshot.revision(), payload.length, index,
            Arrays.copyOfRange(payload, index * CHUNK_BYTES, Math.min(payload.length, (index + 1) * CHUNK_BYTES)));
    }

    public UUID session() {
        return session;
    }

    public long sequence() {
        return sequence;
    }

    public int totalBytes() {
        return totalBytes;
    }

    public int index() {
        return index;
    }

    public int chunks() {
        return (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    public byte[] bytes() {
        return bytes;
    }

    private void validate() {
        if (session == null || sequence < 0 || totalBytes <= 0 || totalBytes > HatchConfigurationSnapshot.MAX_BYTES
            || index < 0 || index >= chunks() || bytes == null || bytes.length != Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES))
            throw new IllegalArgumentException("Invalid hatch configuration chunk bounds");
    }

    @Override
    protected void read(ByteBuf buffer) {
        if (buffer.readableBytes() < 36 || buffer.readableBytes() > CHUNK_BYTES + 36)
            throw new IllegalArgumentException("Invalid hatch configuration message length");
        session = new UUID(buffer.readLong(), buffer.readLong());
        sequence = buffer.readLong();
        totalBytes = buffer.readInt();
        index = buffer.readInt();
        int length = buffer.readInt();
        if (length < 0 || length > CHUNK_BYTES || length != buffer.readableBytes())
            throw new IllegalArgumentException("Invalid hatch configuration chunk length");
        bytes = new byte[length];
        buffer.readBytes(bytes);
        validate();
    }

    @Override
    protected void write(ByteBuf buffer) {
        validate();
        buffer.writeLong(session.getMostSignificantBits());
        buffer.writeLong(session.getLeastSignificantBits());
        buffer.writeLong(sequence);
        buffer.writeInt(totalBytes);
        buffer.writeInt(index);
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    @Override
    protected void handleClientConnection(Object connection) {
        PrototypeEngineering.proxy.receiveHatchConfiguration(this, connection);
    }
}
