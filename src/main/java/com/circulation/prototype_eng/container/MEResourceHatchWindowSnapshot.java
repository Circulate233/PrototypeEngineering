package com.circulation.prototype_eng.container;

import ae2.api.stacks.GenericStack;
import ae2.container.guisync.PacketWritable;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bounded native-slot view; output identities never become editable marker identities.
 * Input and output lists are borrowed read-only and must remain unchanged while this snapshot is retained.
 */
public record MEResourceHatchWindowSnapshot(long revision, long markRevision, int inputFirst, int inputTotal,
                                            int outputFirst, int outputTotal, List<Input> inputs,
                                            List<GenericStack> outputs) implements PacketWritable {
    public static final int INPUT_CELLS = 24;
    public static final int OUTPUT_CELLS = 40;
    public static final MEResourceHatchWindowSnapshot EMPTY = new MEResourceHatchWindowSnapshot(0, -1, 0, 0, 0, 0,
        List.of(), List.of());

    public MEResourceHatchWindowSnapshot {
        if (revision < 0 || markRevision < -1 || inputFirst < 0 || inputFirst > inputTotal
            || outputFirst < 0 || outputFirst > outputTotal || inputs.size() > INPUT_CELLS
            || outputs.size() > OUTPUT_CELLS || inputs.size() > inputTotal - inputFirst
            || outputs.size() > outputTotal - outputFirst)
            throw new IllegalArgumentException("Invalid ME assembly display window");
    }

    @SuppressWarnings("unused") // AE GuiSync invokes the exact PacketWritable constructor.
    public MEResourceHatchWindowSnapshot(ByteBuf data) {
        this(data.readLong(), data.readLong(), data.readInt(), data.readInt(), data.readInt(), data.readInt(),
            readInput(new PacketBuffer(data)), readOutput(new PacketBuffer(data)));
    }

    private static List<Input> readInput(PacketBuffer data) {
        int count = data.readUnsignedByte();
        if (count > INPUT_CELLS) throw new IllegalArgumentException("Oversized ME input display");
        var values = new ObjectArrayList<Input>(count);
        for (int i = 0; i < count; i++)
            values.add(new Input(GenericStack.readBuffer(data), GenericStack.readBuffer(data)));
        return values;
    }

    private static List<GenericStack> readOutput(PacketBuffer data) {
        int count = data.readUnsignedByte();
        if (count > OUTPUT_CELLS) throw new IllegalArgumentException("Oversized ME output display");
        var values = new ObjectArrayList<GenericStack>(count);
        for (int i = 0; i < count; i++) {
            var stack = GenericStack.readBuffer(data);
            if (stack == null || stack.amount() <= 0) throw new IllegalArgumentException("Invalid ME output display");
            values.add(stack);
        }
        return values;
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        data.writeLong(revision).writeLong(markRevision).writeInt(inputFirst).writeInt(inputTotal)
            .writeInt(outputFirst).writeInt(outputTotal).writeByte(inputs.size());
        var packet = new PacketBuffer(data);
        for (int index2 = 0; index2 < inputs.size(); index2++) {
            var value = inputs.get(index2);
            GenericStack.writeBuffer(value.mark, packet);
            GenericStack.writeBuffer(value.actual, packet);
        }
        data.writeByte(outputs.size());
        for (int index = 0; index < outputs.size(); index++) {
            var stack = outputs.get(index);
            GenericStack.writeBuffer(stack, packet);
        }
    }

    public @Nullable GenericStack mark(int cell) {
        return cell >= 0 && cell < inputs.size() ? inputs.get(cell).mark : null;
    }

    public @Nullable GenericStack actual(int cell) {
        return cell >= 0 && cell < inputs.size() ? inputs.get(cell).actual : null;
    }

    public @Nullable GenericStack output(int cell) {
        return cell >= 0 && cell < outputs.size() ? outputs.get(cell) : null;
    }

    public record Input(@Nullable GenericStack mark, @Nullable GenericStack actual) {
    }
}
