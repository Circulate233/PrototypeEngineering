package com.circulation.prototype_eng.machine.render;

import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A bounded single pending payload; all native packets retain the original Display metadata. */
public final class ControllerRenderChunks {
    public static final int CHUNK_BYTES = 16 * 1024;
    @SuppressWarnings("unused") // Public allocation bound for native-channel consumers.
    public static final int MAX_CHUNKS = ControllerRenderSnapshotCodec.MAX_BYTES / CHUNK_BYTES;

    private ControllerRenderChunks() {
    }

    public static List<Part> split(MachineRenderSnapshot snapshot) {
        byte[] bytes = ControllerRenderSnapshotCodec.encode(snapshot);
        int count = chunks(bytes.length);
        var parts = new ObjectArrayList<Part>(count);
        for (int i = 0; i < count; i++)
            parts.add(new Part(snapshot.display(), snapshot.catalog(), bytes.length, i,
                Arrays.copyOfRange(bytes, i * CHUNK_BYTES, Math.min(bytes.length, (i + 1) * CHUNK_BYTES))));
        return List.copyOf(parts);
    }

    private static int chunks(int bytes) {
        if (bytes <= 0 || bytes > ControllerRenderSnapshotCodec.MAX_BYTES)
            throw new IllegalArgumentException("Invalid machine render total byte length");
        return (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    public record Part(ControllerDisplayData display, MachineRenderSnapshot.CatalogToken catalog,
                       int totalBytes, int index, byte[] bytes) {
        public Part {
            Objects.requireNonNull(display);
            Objects.requireNonNull(catalog);
            Objects.requireNonNull(bytes);
            int count = chunks(totalBytes);
            if (index < 0 || index >= count || bytes.length != Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES))
                throw new IllegalArgumentException("Invalid machine render chunk size/index");
            bytes = bytes.clone();
        }

        public static Part decode(ControllerDisplayData display, NBTTagCompound tag) {
            StrictNbt.requireKeys(tag, "Version", "SessionMost", "SessionLeast", "CatalogSequence", "Total", "Index", "Count", "Bytes");
            if (StrictNbt.requireInt(tag, "Version") != 1 || !tag.hasKey("Bytes", Constants.NBT.TAG_BYTE_ARRAY))
                throw new IllegalArgumentException("Invalid machine render chunk schema");
            var part = new Part(display, new MachineRenderSnapshot.CatalogToken(new UUID(StrictNbt.requireLong(tag, "SessionMost"),
                StrictNbt.requireLong(tag, "SessionLeast")), StrictNbt.requireLong(tag, "CatalogSequence")),
                StrictNbt.requireInt(tag, "Total"), StrictNbt.requireInt(tag, "Index"), tag.getByteArray("Bytes"));
            if (StrictNbt.requireInt(tag, "Count") != part.count())
                throw new IllegalArgumentException("Conflicting machine render chunk count");
            return part;
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public int count() {
            return chunks(totalBytes);
        }

        /** Render sibling only; native tag writes Display separately from the same frozen part. */
        public NBTTagCompound encode() {
            var tag = new NBTTagCompound();
            tag.setInteger("Version", 1);
            tag.setLong("SessionMost", catalog.session().getMostSignificantBits());
            tag.setLong("SessionLeast", catalog.session().getLeastSignificantBits());
            tag.setLong("CatalogSequence", catalog.sequence());
            tag.setInteger("Total", totalBytes);
            tag.setInteger("Index", index);
            tag.setInteger("Count", count());
            tag.setByteArray("Bytes", bytes.clone());
            return tag;
        }
    }

    public static final class Assembler {
        @Nullable
        private Part header;
        @Nullable
        private ControllerDisplayData withdrawnThrough;
        private byte @Nullable [][] parts;
        private byte @Nullable [][] acceptedParts;
        private int received;
        private boolean acceptedComplete;
        private boolean retired;

        public @Nullable Part header() {
            return header;
        }

        /** Metadata-only revocation still establishes a sequence floor; late Render cannot revive it. */
        public boolean withdraw(ControllerDisplayData display) {
            if (retired) return false;
            if (withdrawnThrough != null && !display.follows(withdrawnThrough)) return false;
            if (header != null && !display.follows(header.display())) return false;
            withdrawnThrough = display;
            parts = null;
            acceptedComplete = false;
            acceptedParts = null;
            return true;
        }

        public boolean current(MachineRenderSnapshot snapshot) {
            return !retired && acceptedComplete && header != null && header.display().equals(snapshot.display()) &&
                header.catalog().equals(snapshot.catalog());
        }

        public void retire() {
            retired = true;
            acceptedComplete = false;
            parts = null;
            acceptedParts = null;
            header = null;
            withdrawnThrough = null;
        }

        public @Nullable MachineRenderSnapshot receive(Part part) {
            if (retired) return null;
            if (withdrawnThrough != null && !part.display().follows(withdrawnThrough)) return null;
            if (header != null) {
                if (!header.catalog().session().equals(part.catalog().session()) || !header.display().type().equals(part.display().type()))
                    return null;
                if (part.display().equals(header.display())) {
                    if (!part.catalog().equals(header.catalog()) || part.totalBytes() != header.totalBytes()) {
                        parts = null;
                        acceptedComplete = false;
                        acceptedParts = null;
                        throw new IllegalArgumentException("Conflicting machine render chunk headers");
                    }
                    if (parts == null) {
                        if (acceptedComplete) {
                            var frozen = Objects.requireNonNull(acceptedParts, "Complete render assembler has no frozen parts");
                            if (!Arrays.equals(frozen[part.index()], part.bytes())) {
                                acceptedComplete = false;
                                acceptedParts = null;
                                throw new IllegalArgumentException("Conflicting repeated completed machine render chunk");
                            }
                        }
                        return null; // Complete/poisoned sequence cannot be revived by replay.
                    }
                } else {
                    if (!part.display().follows(header.display()) || part.catalog().sequence() < header.catalog().sequence())
                        return null;
                    parts = null;
                    acceptedComplete = false;
                    acceptedParts = null;
                }
            }
            if (parts == null) {
                header = part;
                parts = new byte[part.count()][];
                received = 0;
                acceptedComplete = false;
            }
            byte[] bytes = part.bytes();
            byte[] previous = parts[part.index()];
            if (previous != null) {
                if (!Arrays.equals(previous, bytes)) {
                    parts = null;
                    acceptedComplete = false;
                    throw new IllegalArgumentException("Conflicting repeated machine render chunk");
                }
                return null;
            }
            parts[part.index()] = bytes;
            if (++received != parts.length) return null;
            byte[] complete = new byte[part.totalBytes()];
            int offset = 0;
            for (var chunk : parts) {
                System.arraycopy(chunk, 0, complete, offset, chunk.length);
                offset += chunk.length;
            }
            var completedParts = parts;
            parts = null;
            var snapshot = ControllerRenderSnapshotCodec.decode(complete);
            if (!snapshot.display().equals(part.display()) || !snapshot.catalog().equals(part.catalog()))
                throw new IllegalArgumentException("Machine render payload differs from its frozen chunk header");
            acceptedComplete = true;
            acceptedParts = completedParts;
            return snapshot;
        }
    }
}
