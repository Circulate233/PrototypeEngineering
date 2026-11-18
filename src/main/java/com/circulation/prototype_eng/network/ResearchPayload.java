package com.circulation.prototype_eng.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/** Ordered, bounded parts that fit the vanilla custom payload limit in both directions. */
public final class ResearchPayload {
    public static final int PART_BYTES = 16 * 1024;
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private ResearchPayload() {
    }

    public static String readText(PacketBuffer data) {
        int size = data.readVarInt();
        if (size < 0 || size > data.readableBytes()) throw new IllegalArgumentException("Invalid research text length");
        return data.readCharSequence(size, StandardCharsets.UTF_8).toString();
    }

    public static void writeText(PacketBuffer data, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        data.writeVarInt(bytes.length);
        data.writeBytes(bytes);
    }

    public static byte[] encode(Consumer<ByteBuf> encoder) {
        var buffer = Unpooled.buffer(256, MAX_BYTES);
        try {
            encoder.accept(buffer);
            var bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static void split(byte[] bytes, Consumer<Part> consumer) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid research payload length");
        var id = UUID.randomUUID();
        for (int offset = 0; offset < bytes.length; offset += PART_BYTES) {
            consumer.accept(new Part(id, bytes.length, offset, Arrays.copyOfRange(bytes, offset, Math.min(offset + PART_BYTES, bytes.length))));
        }
    }

    public record Part(UUID id, int length, int offset, byte[] bytes) {
        public Part {
            if (length > MAX_BYTES || offset < 0 || offset >= length || offset % PART_BYTES != 0
                || bytes.length != Math.min(PART_BYTES, length - offset)) {
                throw new IllegalArgumentException("Invalid research payload part");
            }
        }

        public static Part read(ByteBuf buffer) {
            if (buffer.readableBytes() <= 24 || buffer.readableBytes() > 24 + PART_BYTES)
                throw new IllegalArgumentException("Invalid research message length");
            var id = new UUID(buffer.readLong(), buffer.readLong());
            int length = buffer.readInt();
            int offset = buffer.readInt();
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new Part(id, length, offset, bytes);
        }

        public void write(ByteBuf buffer) {
            buffer.writeLong(id.getMostSignificantBits());
            buffer.writeLong(id.getLeastSignificantBits());
            buffer.writeInt(length);
            buffer.writeInt(offset);
            buffer.writeBytes(bytes);
        }
    }

    public static final class Assembly {
        private @Nullable UUID id;
        private byte @Nullable [] pending;
        private int offset;

        public void clear() {
            id = null;
            pending = null;
            offset = 0;
        }

        public void accept(Part part, Consumer<ByteBuf> complete) {
            if (part.offset() == 0) {
                id = part.id();
                pending = new byte[part.length()];
                offset = 0;
            }
            if (!part.id().equals(id) || pending == null || pending.length != part.length() || offset != part.offset()) {
                clear();
                throw new IllegalArgumentException("Out of order research payload part");
            }
            System.arraycopy(part.bytes(), 0, pending, offset, part.bytes().length);
            offset += part.bytes().length;
            if (offset == pending.length) {
                var buffer = Unpooled.wrappedBuffer(pending);
                clear();
                try {
                    complete.accept(buffer);
                    if (buffer.isReadable()) throw new IllegalArgumentException("Trailing bytes in research payload");
                } finally {
                    buffer.release();
                }
            }
        }
    }
}
