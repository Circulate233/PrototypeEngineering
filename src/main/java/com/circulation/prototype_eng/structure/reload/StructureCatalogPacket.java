package com.circulation.prototype_eng.structure.reload;

import com.circulation.prototype_eng.PrototypeEngineering;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Each 16 KiB message is below vanilla's actual 1 MiB S2C custom-payload limit.
 */
public final class StructureCatalogPacket implements IMessage {
    public static final int CHUNK_BYTES = 16 * 1024;
    private UUID session;
    private long sequence;
    private int totalBytes;
    private int index;
    private byte[] bytes;

    public StructureCatalogPacket() {
    }

    public StructureCatalogPacket(UUID session, long sequence, int totalBytes, int index, byte[] bytes) {
        this.session = Objects.requireNonNull(session);
        this.sequence = sequence;
        this.totalBytes = totalBytes;
        this.index = index;
        this.bytes = bytes.clone();
        validate();
    }

    public static StructureCatalogPacket part(StructureReloadCoordinator.Publication publication, byte[] payload, int index) {
        return new StructureCatalogPacket(publication.view().session(), publication.view().sequence(), payload.length, index,
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
        return bytes.clone();
    }

    private void validate() {
        if (session == null || sequence < 0 || totalBytes <= 0 || totalBytes > StructureCatalogCodec.MAX_BYTES
            || index < 0 || index >= chunks() || bytes == null || bytes.length != Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES))
            throw new IllegalArgumentException("Invalid structure catalog chunk bounds");
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() < 36 || buffer.readableBytes() > CHUNK_BYTES + 36)
            throw new IllegalArgumentException("Invalid structure catalog message length");
        session = new UUID(buffer.readLong(), buffer.readLong());
        sequence = buffer.readLong();
        totalBytes = buffer.readInt();
        index = buffer.readInt();
        int length = buffer.readInt();
        if (length < 0 || length > CHUNK_BYTES || length != buffer.readableBytes())
            throw new IllegalArgumentException("Invalid structure catalog chunk length");
        bytes = new byte[length];
        buffer.readBytes(bytes);
        validate();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        validate();
        buffer.writeLong(session.getMostSignificantBits());
        buffer.writeLong(session.getLeastSignificantBits());
        buffer.writeLong(sequence);
        buffer.writeInt(totalBytes);
        buffer.writeInt(index);
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    public static final class Handler implements IMessageHandler<StructureCatalogPacket, IMessage> {
        @Override
        public IMessage onMessage(StructureCatalogPacket message, MessageContext context) {
            if (context.side != Side.CLIENT)
                throw new IllegalStateException("Structure catalog is server-to-client only");
            PrototypeEngineering.proxy.receiveStructureCatalog(message, context.netHandler);
            return null;
        }
    }
}
