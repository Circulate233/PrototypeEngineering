package com.circulation.prototype_eng.structure.reload;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
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
import java.util.Map;
import java.util.UUID;

/**
 * Bounded binary presentation protocol; it neither serializes nor executes matcher callbacks.
 */
public final class StructureCatalogCodec {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int VERSION = 2;
    private static final int MAX_STRING = 65536;

    private StructureCatalogCodec() {
    }

    public static byte[] encode(StructureCatalogView view) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new BoundedOutput(bytes))) {
            output.writeInt(VERSION);
            output.writeLong(view.session().getMostSignificantBits());
            output.writeLong(view.session().getLeastSignificantBits());
            output.writeLong(view.sequence());
            count(output, view.nodes().size());
            for (var node : view.nodes()) {
                id(output, node.id());
                output.writeByte(node.orientation().getSide(RelativeSide.FRONT).getIndex());
                output.writeByte(node.orientation().getSide(RelativeSide.TOP).getIndex());
                output.writeBoolean(node.hideWorldBlocks());
                output.writeByte(node.kind().ordinal());
                position(output, node.offset());
                output.writeInt(node.minCount());
                output.writeInt(node.maxCount());
                position(output, node.sliceOffset());
                count(output, node.cells().size());
                for (var cell : node.cells()) position(output, cell);
                count(output, node.children().size());
                for (int child : node.children()) output.writeInt(child);
                displays(output, node.displays());
            }
            count(output, view.entries().size());
            for (var entry : view.entries().entrySet()) {
                id(output, entry.getKey());
                output.writeInt(entry.getValue().node());
                nullable(output, entry.getValue().translationKey());
                displays(output, entry.getValue().displays());
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode structure catalog view", failure);
        }
        return bytes.toByteArray();
    }

    public static StructureCatalogView decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Structure catalog payload is too large");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readInt();
            if (version < 1 || version > VERSION)
                throw new IllegalArgumentException("Unknown structure catalog protocol version");
            var session = new UUID(input.readLong(), input.readLong());
            long sequence = input.readLong();
            var nodes = new ObjectArrayList<StructureCatalogView.Node>();
            int nodeCount = count(input);
            for (int i = 0; i < nodeCount; i++) {
                var id = id(input);
                int front = input.readUnsignedByte(), top = input.readUnsignedByte();
                if (front > 5 || top > 5 || EnumFacing.byIndex(front).getAxis() == EnumFacing.byIndex(top).getAxis())
                    throw new IllegalArgumentException("Invalid preview orientation");
                var orientation = BlockOrientation.get(EnumFacing.byIndex(front), EnumFacing.byIndex(top));
                boolean hide = bool(input);
                int kind = input.readUnsignedByte();
                if (kind >= StructurePreviewProvider.Kind.VALUES.length)
                    throw new IllegalArgumentException("Unknown preview shape kind");
                var offset = position(input);
                int min = input.readInt(), max = input.readInt();
                var sliceOffset = position(input);
                var cells = new ObjectArrayList<BlockPos>();
                int cellCount = count(input);
                for (int c = 0; c < cellCount; c++) cells.add(position(input));
                var children = new IntArrayList();
                int childCount = count(input);
                for (int c = 0; c < childCount; c++) children.add(input.readInt());
                nodes.add(new StructureCatalogView.Node(id, orientation, hide, StructurePreviewProvider.Kind.VALUES[kind], offset, min, max, sliceOffset, cells, children, version == 2 ? displays(input, version) : BlockPos2ObjectMap.emptyMap()));
            }
            var entries = new Object2ObjectLinkedOpenHashMap<ResourceLocation, StructureCatalogView.Entry>();
            int entryCount = count(input);
            for (int e = 0; e < entryCount; e++) {
                var id = id(input);
                int node = input.readInt();
                String name = nullable(input);
                var displays = displays(input, version);
                if (entries.putIfAbsent(id, new StructureCatalogView.Entry(node, name, displays)) != null)
                    throw new IllegalArgumentException("Duplicate preview structure ID");
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing structure catalog payload");
            return new StructureCatalogView(session, sequence, nodes, entries);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot decode structure catalog view", failure);
        }
    }

    private static void displays(DataOutputStream output, BlockPos2ObjectMap<StructureDisplay> displays) throws IOException {
        count(output, displays.size());
        for (var entry : displays.long2ObjectEntrySet()) {
            position(output, BlockPos.fromLong(entry.getLongKey()));
            var display = entry.getValue();
            string(output, display.key());
            nullable(output, display.tileNbt());
            output.writeBoolean(display.complete());
            count(output, display.options().size());
            for (var option : display.options()) {
                id(output, option.blockId());
                output.writeByte(option.meta());
                fields(output, option.nbt());
                fields(output, option.properties());
            }
        }
    }

    private static BlockPos2ObjectMap<StructureDisplay> displays(DataInputStream input, int version) throws IOException {
        var displays = new BlockPos2ObjectMap<StructureDisplay>();
        int displayCount = count(input);
        for (int d = 0; d < displayCount; d++) {
            var position = position(input);
            String key = string(input), tileNbt = nullable(input);
            boolean complete = version < 2 || bool(input);
            var options = new ObjectArrayList<StructureDisplay.BlockOption>();
            int optionCount = count(input);
            for (int o = 0; o < optionCount; o++) {
                var block = id(input);
                int meta = input.readUnsignedByte();
                var nbt = fields(input);
                var properties = version >= 2 ? fields(input) : Map.<String, String>of();
                options.add(new StructureDisplay.BlockOption(block, meta, nbt, properties));
            }
            if (displays.putIfAbsent(position, new StructureDisplay(key, options, tileNbt, complete)) != null)
                throw new IllegalArgumentException("Duplicate preview display coordinate");
        }
        return displays;
    }

    private static void fields(DataOutputStream output, Map<String, String> fields) throws IOException {
        count(output, fields.size());
        for (var field : fields.entrySet()) {
            string(output, field.getKey());
            string(output, field.getValue());
        }
    }

    private static Map<String, String> fields(DataInputStream input) throws IOException {
        var fields = new Object2ObjectLinkedOpenHashMap<String, String>();
        int fieldCount = count(input);
        for (int f = 0; f < fieldCount; f++) {
            String name = string(input);
            if (name.isEmpty() || fields.putIfAbsent(name, string(input)) != null)
                throw new IllegalArgumentException("Duplicate or empty preview state field");
        }
        return fields;
    }

    private static void count(DataOutputStream output, int value) throws IOException {
        if (value < 0 || value > StructureCatalogView.MAX_NODES)
            throw new IllegalArgumentException("Structure view collection is too large");
        output.writeInt(value);
    }

    private static int count(DataInputStream input) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > StructureCatalogView.MAX_NODES || value > input.available())
            throw new IllegalArgumentException("Invalid structure view collection length");
        return value;
    }

    private static void string(DataOutputStream output, String text) throws IOException {
        if (text.length() > MAX_STRING) throw new IllegalArgumentException("Structure view string is too large");
        var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (bytes.length > MAX_STRING) throw new IllegalArgumentException("Structure view string is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String string(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING || length > input.available())
            throw new IllegalArgumentException("Invalid structure view string length");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                     .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
    }

    private static ResourceLocation id(DataInputStream input) throws IOException {
        String value = string(input);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid structure view resource ID");
        return new ResourceLocation(value);
    }

    private static void id(DataOutputStream output, ResourceLocation id) throws IOException {
        String value = id.toString();
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid structure view resource ID");
        string(output, value);
    }

    private static boolean bool(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid structure view boolean");
        return value != 0;
    }

    private static void nullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) string(output, value);
    }

    private static String nullable(DataInputStream input) throws IOException {
        return bool(input) ? string(input) : null;
    }

    private static void position(DataOutputStream output, BlockPos position) throws IOException {
        output.writeInt(position.getX());
        output.writeInt(position.getY());
        output.writeInt(position.getZ());
    }

    private static BlockPos position(DataInputStream input) throws IOException {
        return new BlockPos(input.readInt(), input.readInt(), input.readInt());
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes;

        private BoundedOutput(ByteArrayOutputStream bytes) {
            this.bytes = bytes;
        }

        @Override
        public void write(int value) {
            check(1);
            bytes.write(value);
        }

        @Override
        public void write(byte @NotNull [] buffer, int offset, int length) {
            check(length);
            bytes.write(buffer, offset, length);
        }

        private void check(int length) {
            if (length < 0 || bytes.size() > MAX_BYTES - length)
                throw new IllegalArgumentException("Structure catalog payload is too large");
        }
    }
}
