package com.circulation.prototype_eng.machine.render;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
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
import java.util.UUID;

/**
 * Full signed coordinates use compact zigzag varints, never lossy BlockPos packed longs.
 */
public final class ControllerRenderSnapshotCodec {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 4096;

    private ControllerRenderSnapshotCodec() {
    }

    /**
     * Same allocation gate for ordinary data carried by the native controller chunk channel.
     */
    public static byte[] encodeCompound(NBTTagCompound tag) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new BoundedOutput(bytes))) {
            CompressedStreamTools.write(tag, out);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode controller data", failure);
        }
        return bytes.toByteArray();
    }

    public static NBTTagCompound decodeCompound(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Controller data exceeds supported scale");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var tag = CompressedStreamTools.read(in, new NBTSizeTracker(MAX_BYTES));
            if (in.available() != 0) throw new IllegalArgumentException("Trailing controller data bytes");
            return tag;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid or incomplete controller data", failure);
        }
    }

    public static byte[] encode(MachineRenderSnapshot snapshot) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new BoundedOutput(bytes))) {
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
            var elements4 = snapshot.occurrences();
            for (int index4 = 0; index4 < elements4.size(); index4++) {
                var node = elements4.get(index4);
                out.writeInt(node.parent());
                out.writeInt(node.childEdge());
                id(out, node.structure());
                position(out, node.origin());
                out.writeInt(node.repetitions());
                out.writeByte(node.kind().ordinal());
                position(out, node.sliceStep());
                out.writeBoolean(node.hideWorldBlocks());
                out.writeInt(node.blocks().size());
                var elements = node.blocks();
                for (int index = 0; index < elements.size(); index++) {
                    var block = elements.get(index);
                    position(out, block);
                }
            }
            // The distinct union and bounds are reconstructed from complete occurrence membership.
            out.writeInt(snapshot.components().size());
            var elements2 = snapshot.components();
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var component = elements2.get(index2);
                id(out, component);
            }
            out.writeBoolean(snapshot.animation() != null);
            if (snapshot.animation() != null) {
                out.writeLong(snapshot.animation().revision());
                out.writeInt(snapshot.animation().layers().size());
                var elements3 = snapshot.animation().layers();
                for (int index3 = 0; index3 < elements3.size(); index3++) {
                    var layer = elements3.get(index3);
                    string(out, layer);
                }
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
            var childCounts = new int[nodes];
            var positions = new ObjectLinkedOpenHashSet<BlockPos>();
            int memberships = 0;
            for (int i = 0; i < nodes; i++) {
                int parent = in.readInt(), edge = in.readInt();
                if (i == 0 ? parent != -1 || edge != -1 : parent < 0 || parent >= i || edge != childCounts[parent]++)
                    throw new IllegalArgumentException("Invalid accepted occurrence parent/child edge");
                var nodeId = id(in);
                var origin = position(in);
                int repetitions = in.readInt();
                if (repetitions < 0 || repetitions > MachineRenderSnapshot.MAX_REPETITIONS)
                    throw new IllegalArgumentException("Accepted repetition count exceeds supported scale");
                int kind = in.readUnsignedByte();
                if (kind >= StructurePreviewProvider.Kind.VALUES.length)
                    throw new IllegalArgumentException("Unknown accepted shape kind");
                var step = position(in);
                if (kind == StructurePreviewProvider.Kind.SLICE.ordinal() && repetitions > 0)
                    MachineRenderSnapshot.sliceAnchor(origin, step, repetitions - 1);
                boolean hide = bool(in);
                int blocks = count(in, MachineRenderSnapshot.MAX_POSITIONS - memberships, 3);
                memberships += blocks;
                var covered = new ObjectArrayList<BlockPos>(blocks);
                for (int b = 0; b < blocks; b++) covered.add(position(in));
                positions.addAll(covered);
                occurrences.add(new MachineRenderSnapshot.Occurrence(parent, edge, nodeId, origin, repetitions,
                    StructurePreviewProvider.Kind.VALUES[kind], step, hide, covered));
            }
            if (display.formed() == occurrences.isEmpty() || !occurrences.isEmpty()
                && (structure == null || !structure.equals(occurrences.getFirst().structure())))
                throw new IllegalArgumentException("Formed display must contain its complete accepted root");
            if (!display.formed() && !positions.isEmpty())
                throw new IllegalArgumentException("Unformed display cannot retain accepted positions");
            int ids = count(in, MachineRenderSnapshot.MAX_OCCURRENCES, 5);
            var components = new ObjectArrayList<ResourceLocation>(ids);
            var componentIds = new ObjectLinkedOpenHashSet<ResourceLocation>();
            for (int i = 0; i < ids; i++) {
                var component = id(in);
                if (!componentIds.add(component)) throw new IllegalArgumentException("Duplicate actual component ID");
                components.add(component);
            }
            MachineAnimationState.Snapshot animation = null;
            if (bool(in)) {
                long revision = in.readLong();
                int size = count(in, MachineAnimationState.MAX_LAYERS, 5);
                var layers = new ObjectArrayList<String>(size);
                for (int i = 0; i < size; i++) layers.add(string(in));
                animation = new MachineAnimationState.Snapshot(revision, layers);
            }
            if (animation != null && !componentIds.contains(MachineAnimationState.TYPE.id()))
                throw new IllegalArgumentException("Animation state has no actual component binding");
            if (in.available() != 0) throw new IllegalArgumentException("Trailing machine render bytes");
            return new MachineRenderSnapshot(display, catalog, controller, structure, occurrences, new ObjectArrayList<>(positions), components, animation);
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
        return new ResourceLocation(string(in));
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
