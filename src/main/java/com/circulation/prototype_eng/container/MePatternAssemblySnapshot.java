package com.circulation.prototype_eng.container;

import ae2.api.stacks.GenericStack;
import ae2.container.guisync.PacketWritable;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A bounded page of real patterns and three independently versioned resource windows. */
public record MePatternAssemblySnapshot(long revision, long patternRevision, int page, int pages, int mode,
                                         List<Entry> patterns, Side input, Side catalyst, Side output) implements PacketWritable {
    public static final int PATTERNS = 36;
    public static final MePatternAssemblySnapshot EMPTY = new MePatternAssemblySnapshot(0, 0, 0, 1, 0,
        List.of(), Side.EMPTY, Side.EMPTY, Side.EMPTY);

    public MePatternAssemblySnapshot {
        if (revision < 0 || patternRevision < 0 || page < 0 || page >= pages
            || mode < 0 || mode >= 5 || patterns.size() > PATTERNS)
            throw new IllegalArgumentException("Invalid ME pattern display");
        patterns = List.copyOf(patterns);
    }

    @SuppressWarnings("unused") // AE GuiSync invokes the exact PacketWritable constructor.
    public MePatternAssemblySnapshot(ByteBuf data) {
        this(data.readLong(), data.readLong(), data.readInt(), data.readInt(), data.readUnsignedByte(),
            readEntries(new PacketBuffer(data), PATTERNS), readSide(new PacketBuffer(data)),
            readSide(new PacketBuffer(data)), readSide(new PacketBuffer(data)));
    }

    private static List<Entry> readEntries(PacketBuffer data, int maximum) {
        int count = data.readUnsignedByte();
        if (count > maximum) throw new IllegalArgumentException("Oversized ME pattern window");
        var entries = new ObjectArrayList<Entry>(count);
        for (int i = 0; i < count; i++) entries.add(new Entry(GenericStack.readBuffer(data)));
        return entries;
    }

    private static Side readSide(PacketBuffer data) {
        return new Side(data.readLong(), data.readInt(), data.readInt(), readEntries(data, 30));
    }

    private static void writeEntries(PacketBuffer data, List<Entry> entries) {
        data.writeByte(entries.size());
        for (var entry : entries) GenericStack.writeBuffer(entry.stack, data);
    }

    private static void writeSide(PacketBuffer data, Side side) {
        data.writeLong(side.revision).writeInt(side.first).writeInt(side.total);
        writeEntries(data, side.entries);
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        data.writeLong(revision).writeLong(patternRevision).writeInt(page).writeInt(pages).writeByte(mode);
        var packet = new PacketBuffer(data);
        writeEntries(packet, patterns);
        writeSide(packet, input);
        writeSide(packet, catalyst);
        writeSide(packet, output);
    }

    public @Nullable GenericStack pattern(int cell) {
        return cell >= 0 && cell < patterns.size() ? patterns.get(cell).stack : null;
    }

    public Side side(int kind) {
        return switch (kind) {
            case 0 -> input;
            case 1 -> catalyst;
            case 2 -> output;
            default -> throw new IllegalArgumentException("Invalid ME pattern buffer");
        };
    }

    public record Side(long revision, int first, int total, List<Entry> entries) {
        private static final Side EMPTY = new Side(0, 0, 0, List.of());

        public Side {
            if (revision < 0 || first < 0 || first > total || entries.size() > 30 || entries.size() > total - first)
                throw new IllegalArgumentException("Invalid ME pattern resource view");
            entries = List.copyOf(entries);
        }

        public @Nullable GenericStack stack(int cell) {
            return cell >= 0 && cell < entries.size() ? entries.get(cell).stack : null;
        }
    }

    public record Entry(@Nullable GenericStack stack) {
        public Entry {
            if (stack != null && stack.amount() <= 0) throw new IllegalArgumentException("Invalid pattern resource amount");
        }
    }
}
