package com.circulation.prototype_eng.network.clientbound;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.network.ConnectionClientboundPacket;
import com.circulation.prototype_eng.recipe.RecipeDefinitionManifestCodec;
import io.netty.buffer.ByteBuf;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Zero payload announces a generation; nonzero payload parts carry its complete ready manifest.
 */
public final class RecipeDefinitionManifestPacket extends ConnectionClientboundPacket {
    public static final int CHUNK_BYTES = 16 * 1024;
    private UUID session;
    private long generation;
    private boolean declarationRequired = true;
    private int totalBytes;
    private int index;
    private byte[] bytes;

    public RecipeDefinitionManifestPacket() {
    }

    public RecipeDefinitionManifestPacket(UUID session, long generation, int totalBytes, int index, byte[] bytes) {
        this.session = Objects.requireNonNull(session);
        this.generation = generation;
        this.totalBytes = totalBytes;
        this.index = index;
        this.bytes = bytes.clone();
        validate();
    }

    public static RecipeDefinitionManifestPacket notice(UUID session, long generation) {
        return new RecipeDefinitionManifestPacket(session, generation, 0, 0, new byte[0]);
    }

    public static RecipeDefinitionManifestPacket notice(UUID session, long generation, boolean declarationRequired) {
        var notice = notice(session, generation);
        notice.declarationRequired = declarationRequired;
        notice.validate();
        return notice;
    }

    public static RecipeDefinitionManifestPacket part(RecipeDefinitionManifest manifest, byte[] payload, int index) {
        return new RecipeDefinitionManifestPacket(manifest.serverSession(), manifest.generation(), payload.length, index,
            Arrays.copyOfRange(payload, index * CHUNK_BYTES, Math.min(payload.length, (index + 1) * CHUNK_BYTES)));
    }

    public UUID session() {
        return session;
    }

    public long generation() {
        return generation;
    }

    public boolean declarationRequired() {
        return declarationRequired;
    }

    public int totalBytes() {
        return totalBytes;
    }

    public int index() {
        return index;
    }

    public int chunks() {
        return notice() ? 1 : (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    public boolean notice() {
        return totalBytes == 0;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    private void validate() {
        if (session == null || generation < 0 || totalBytes < 0 || totalBytes > RecipeDefinitionManifestCodec.MAX_BYTES
            || index < 0 || index >= chunks() || bytes == null || bytes.length != Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES))
            throw new IllegalArgumentException("Recipe manifest chunk bounds");
        if (!declarationRequired && !notice())
            throw new IllegalArgumentException("Ready manifests always require a declaration");
        if (!declarationRequired && generation != 0)
            throw new IllegalArgumentException("Only the server static baseline may omit a declaration");
    }

    @Override
    protected void read(ByteBuf buffer) {
        if (buffer.readableBytes() < 37 || buffer.readableBytes() > CHUNK_BYTES + 37)
            throw new IllegalArgumentException("Recipe manifest message bounds");
        session = new UUID(buffer.readLong(), buffer.readLong());
        generation = buffer.readLong();
        int requirement = buffer.readUnsignedByte();
        if (requirement > 1) throw new IllegalArgumentException("Invalid recipe declaration requirement flag");
        declarationRequired = requirement == 1;
        totalBytes = buffer.readInt();
        index = buffer.readInt();
        int length = buffer.readInt();
        if (length < 0 || length > CHUNK_BYTES || length != buffer.readableBytes())
            throw new IllegalArgumentException("Recipe manifest chunk length");
        bytes = new byte[length];
        buffer.readBytes(bytes);
        validate();
    }

    @Override
    protected void write(ByteBuf buffer) {
        validate();
        buffer.writeLong(session.getMostSignificantBits());
        buffer.writeLong(session.getLeastSignificantBits());
        buffer.writeLong(generation);
        buffer.writeByte(declarationRequired ? 1 : 0);
        buffer.writeInt(totalBytes);
        buffer.writeInt(index);
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    @Override
    protected void handleClientConnection(Object connection) {
        PrototypeEngineering.proxy.receiveRecipeDefinitions(this, connection);
    }
}
