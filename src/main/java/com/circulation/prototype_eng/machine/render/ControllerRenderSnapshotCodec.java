package com.circulation.prototype_eng.machine.render;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Full signed coordinates use compact zigzag varints, never lossy BlockPos packed longs. */
public final class ControllerRenderSnapshotCodec {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 4096;

    private ControllerRenderSnapshotCodec() {
    }

    public static byte[] encode(MachineRenderSnapshot snapshot) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new BoundedOutput(bytes))) {
            out.writeInt(VERSION);
            var display = snapshot.display();
            id(out, display.type());
            out.writeByte(display.orientation().getSide(RelativeSide.FRONT).getIndex());
            out.writeByte(display.orientation().getSide(RelativeSide.TOP).getIndex());
            out.writeBoolean(display.formed());
            out.writeLong(display.generation());
            out.writeLong(display.sequence());
            out.writeLong(snapshot.catalog().session().getMostSignificantBits());
            out.writeLong(snapshot.catalog().session().getLeastSignificantBits());
            out.writeLong(snapshot.catalog().sequence());
            position(out, snapshot.controller());
            out.writeBoolean(snapshot.structure() != null);
            if (snapshot.structure() != null) id(out, snapshot.structure());
            out.writeInt(snapshot.occurrences().size());
            for (var node : snapshot.occurrences()) {
                out.writeInt(node.parent());
                out.writeInt(node.childEdge());
                id(out, node.structure());
                position(out, node.origin());
                out.writeInt(node.repetitions());
                out.writeByte(node.kind().ordinal());
                position(out, node.sliceStep());
                out.writeBoolean(node.hideWorldBlocks());
                out.writeInt(node.blocks().size());
                for (var block : node.blocks()) position(out, block);
            }
            // The distinct union and bounds are reconstructed from complete occurrence membership.
            out.writeInt(snapshot.components().size());
            for (var component : snapshot.components()) id(out, component);
            out.writeBoolean(snapshot.animation() != null);
            if (snapshot.animation() != null) {
                out.writeLong(snapshot.animation().revision());
                out.writeInt(snapshot.animation().layers().size());
                for (var layer : snapshot.animation().layers()) string(out, layer);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode machine render snapshot", failure);
        }
        return bytes.toByteArray();
    }

    public static MachineRenderSnapshot decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Machine render payload exceeds supported scale");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Unknown machine render schema");
            var type = id(in);
            int front = in.readUnsignedByte(), top = in.readUnsignedByte();
            if (front > 5 || top > 5 || EnumFacing.byIndex(front).getAxis() == EnumFacing.byIndex(top).getAxis())
                throw new IllegalArgumentException("Invalid machine render orientation");
            var display = new ControllerDisplayData(type, BlockOrientation.get(EnumFacing.byIndex(front), EnumFacing.byIndex(top)),
                bool(in), in.readLong(), in.readLong());
            var catalog = new MachineRenderSnapshot.CatalogToken(new UUID(in.readLong(), in.readLong()), in.readLong());
            var controller = position(in);
            ResourceLocation structure = bool(in) ? id(in) : null;
            int nodes = count(in, MachineRenderSnapshot.MAX_OCCURRENCES, 19);
            var occurrences = new ObjectArrayList<MachineRenderSnapshot.Occurrence>(nodes);
            var positions = new ObjectLinkedOpenHashSet<BlockPos>();
            int memberships = 0;
            for (int i = 0; i < nodes; i++) {
                int parent = in.readInt(), edge = in.readInt();
                var nodeId = id(in);
                var origin = position(in);
                int repetitions = in.readInt();
                int kind = in.readUnsignedByte();
                if (kind >= StructurePreviewProvider.Kind.values().length)
                    throw new IllegalArgumentException("Unknown accepted shape kind");
                var step = position(in);
                boolean hide = bool(in);
                int blocks = count(in, MachineRenderSnapshot.MAX_POSITIONS - memberships, 3);
                memberships += blocks;
                var covered = new ObjectArrayList<BlockPos>(blocks);
                for (int b = 0; b < blocks; b++) covered.add(position(in));
                positions.addAll(covered);
                occurrences.add(new MachineRenderSnapshot.Occurrence(parent, edge, nodeId, origin, repetitions,
                    StructurePreviewProvider.Kind.values()[kind], step, hide, covered));
            }
            int ids = count(in, MachineRenderSnapshot.MAX_OCCURRENCES, 5);
            var components = new ObjectArrayList<ResourceLocation>(ids);
            for (int i = 0; i < ids; i++) components.add(id(in));
            MachineAnimationState.Snapshot animation = null;
            if (bool(in)) {
                long revision = in.readLong();
                int size = count(in, MachineAnimationState.MAX_LAYERS, 5);
                var layers = new ObjectArrayList<String>(size);
                for (int i = 0; i < size; i++) layers.add(string(in));
                animation = new MachineAnimationState.Snapshot(revision, layers);
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing machine render bytes");
            return new MachineRenderSnapshot(display, catalog, controller, structure, occurrences, List.copyOf(positions), components, animation);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid or incomplete machine render payload", failure);
        }
    }

    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid machine render boolean");
        return value == 1;
    }

    private static int count(DataInputStream in, int maximum, int minBytes) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > maximum || (long) size * minBytes > in.available())
            throw new IllegalArgumentException("Invalid machine render collection length");
        return size;
    }

    private static void id(DataOutputStream out, ResourceLocation value) throws IOException {
        string(out, value.toString());
    }

    private static ResourceLocation id(DataInputStream in) throws IOException {
        return StrictNbt.requireId(string(in));
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                                           .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        if (buffer.remaining() > MAX_STRING_BYTES) throw new IllegalArgumentException("Machine render string too long");
        out.writeInt(buffer.remaining());
        while (buffer.hasRemaining()) out.writeByte(buffer.get());
    }

    private static String string(DataInputStream in) throws IOException {
        int length = count(in, MAX_STRING_BYTES, 1);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                     .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void position(DataOutputStream out, BlockPos position) throws IOException {
        signed(out, position.getX());
        signed(out, position.getY());
        signed(out, position.getZ());
    }

    private static BlockPos position(DataInputStream in) throws IOException {
        return new BlockPos(signed(in), signed(in), signed(in));
    }

    private static void signed(DataOutputStream out, int number) throws IOException {
        int value = (number << 1) ^ (number >> 31);
        while ((value & ~127) != 0) {
            out.writeByte((value & 127) | 128);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int signed(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int value = in.readUnsignedByte();
            if (shift == 28 && (value & 240) != 0)
                throw new IllegalArgumentException("Render coordinate varint overflow");
            result |= (value & 127) << shift;
            if ((value & 128) == 0) {
                if (shift != 0 && (value & 127) == 0)
                    throw new IllegalArgumentException("Noncanonical render coordinate varint");
                return (result >>> 1) ^ -(result & 1);
            }
        }
        throw new IllegalArgumentException("Render coordinate varint overflow");
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes;

        private BoundedOutput(ByteArrayOutputStream bytes) {
            this.bytes = bytes;
        }

        @Override
        public void write(int value) {
            if (bytes.size() == MAX_BYTES)
                throw new IllegalArgumentException("Machine render payload exceeds supported scale");
            bytes.write(value);
        }

        @Override
        public void write(byte @NotNull [] source, int offset, int length) {
            if (length > MAX_BYTES - bytes.size())
                throw new IllegalArgumentException("Machine render payload exceeds supported scale");
            bytes.write(source, offset, length);
        }
    }
}
