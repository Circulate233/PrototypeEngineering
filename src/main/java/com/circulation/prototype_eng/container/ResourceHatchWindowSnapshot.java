package com.circulation.prototype_eng.container;

import ae2.api.stacks.GenericStack;
import ae2.container.guisync.PacketWritable;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** One atomic resource display revision; vanilla slot packets never write this data. */
public record ResourceHatchWindowSnapshot(long revision, Side input, Side output) implements PacketWritable {
    public static final int MAX_CELLS = 72;
    public static final ResourceHatchWindowSnapshot EMPTY = new ResourceHatchWindowSnapshot(0, Side.EMPTY, Side.EMPTY);

    public ResourceHatchWindowSnapshot {
        if (revision < 0 || input == null || output == null) throw new IllegalArgumentException("Invalid hatch window");
    }

    @SuppressWarnings("unused") // AE GuiSync invokes this exact PacketWritable constructor.
    public ResourceHatchWindowSnapshot(ByteBuf data) {
        this(data.readLong(), readSide(new PacketBuffer(data)), readSide(new PacketBuffer(data)));
    }

    private static Side readSide(PacketBuffer data) {
        int first = data.readVarInt(), total = data.readVarInt(), used = data.readVarInt(), types = data.readVarInt();
        int count = data.readUnsignedByte();
        if (count > MAX_CELLS) throw new IllegalArgumentException("Oversized hatch window");
        var entries = new ObjectArrayList<Entry>(count);
        for (int i = 0; i < count; i++) entries.add(new Entry(GenericStack.readBuffer(data), data.readVarLong()));
        return new Side(first, total, used, types, entries);
    }

    private static void writeSide(PacketBuffer data, Side side) {
        data.writeVarInt(side.firstSlot).writeVarInt(side.totalSlots).writeVarInt(side.usedSlots).writeVarInt(side.usedTypes);
        data.writeByte(side.entries.size());
        for (var entry : side.entries) {
            GenericStack.writeBuffer(entry.stack, data);
            data.writeVarLong(entry.capacity);
        }
    }

    public Side side(boolean inputSide) {
        return inputSide ? input : output;
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        data.writeLong(revision);
        writeSide(new PacketBuffer(data), input);
        writeSide(new PacketBuffer(data), output);
    }

    public record Side(int firstSlot, int totalSlots, int usedSlots, int usedTypes, List<Entry> entries) {
        static final Side EMPTY = new Side(0, 0, 0, 0, List.of());

        public Side {
            if (firstSlot < 0 || firstSlot > totalSlots || usedSlots > totalSlots
                || usedTypes < 0 || usedTypes > usedSlots || entries.size() > MAX_CELLS
                || entries.size() > totalSlots - firstSlot)
                throw new IllegalArgumentException("Invalid hatch side window");
            entries = List.copyOf(entries);
        }

        public @Nullable GenericStack stack(int cell) {
            return cell >= 0 && cell < entries.size() ? entries.get(cell).stack : null;
        }

        public int logicalSlot(int cell) {
            return cell >= 0 && cell < entries.size() ? firstSlot + cell : -1;
        }
    }

    public record Entry(@Nullable GenericStack stack, long capacity) {
        public Entry {
            if (capacity < 0 || stack != null && (stack.amount() <= 0 || stack.amount() > capacity))
                throw new IllegalArgumentException("Invalid hatch display quantity");
        }
    }
}
