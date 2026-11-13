package com.circulation.prototype_eng.container;

import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.network.resource.NetworkResourcePackets;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;

import java.util.List;

/**
 * AE GuiSync packet: fixed resource definitions on addition, indexed double deltas thereafter.
 * Completed lists are borrowed unchanged until sent. A full packet resets the client's view.
 */
public record NetworkCenterResourcePacket(boolean full, IntList removed, List<Definition> added,
                                          List<Change> changed) implements PacketWritable {
    public static final NetworkCenterResourcePacket EMPTY = new NetworkCenterResourcePacket(true, IntLists.emptyList(), List.of(), List.of());
    public static final int OCCUPIED = 1, CAPACITY = 2;

    /**
     * AE GuiSync calls this constructor for the packet's declared concrete type.
     */
    @SuppressWarnings("unused") // AE's PacketWritable field invokes this constructor reflectively.
    public NetworkCenterResourcePacket(ByteBuf data) {
        this(data.readBoolean(), readRemoved(new PacketBuffer(data)), readAdded(new PacketBuffer(data)), readChanges(new PacketBuffer(data)));
    }

    private static IntList readRemoved(PacketBuffer data) {
        int count = data.readVarInt();
        var removed = new IntArrayList(count);
        for (int index = 0; index < count; index++) removed.add(data.readVarInt());
        return removed;
    }

    private static List<Definition> readAdded(PacketBuffer data) {
        int count = data.readVarInt();
        var added = new ObjectArrayList<Definition>(count);
        for (int index = 0; index < count; index++)
            added.add(new Definition(data.readVarInt(), NetworkResourcePackets.read(data), data.readDouble(), data.readDouble()));
        return added;
    }

    private static List<Change> readChanges(PacketBuffer data) {
        int count = data.readVarInt();
        var changes = new ObjectArrayList<Change>(count);
        for (int index = 0; index < count; index++) {
            int resource = data.readVarInt(), fields = data.readUnsignedByte();
            changes.add(new Change(resource, fields, (fields & OCCUPIED) != 0 ? data.readDouble() : 0,
                (fields & CAPACITY) != 0 ? data.readDouble() : 0));
        }
        return changes;
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        var packet = new PacketBuffer(data);
        packet.writeBoolean(full);
        packet.writeVarInt(removed.size());
        for (int index = 0; index < removed.size(); index++) packet.writeVarInt(removed.getInt(index));
        packet.writeVarInt(added.size());
        for (var entry : added) {
            packet.writeVarInt(entry.index);
            NetworkResourcePackets.write(packet, entry.resource);
            packet.writeDouble(entry.occupied);
            packet.writeDouble(entry.capacity);
        }
        packet.writeVarInt(changed.size());
        for (var entry : changed) {
            packet.writeVarInt(entry.index);
            packet.writeByte(entry.fields);
            if ((entry.fields & OCCUPIED) != 0) packet.writeDouble(entry.occupied);
            if ((entry.fields & CAPACITY) != 0) packet.writeDouble(entry.capacity);
        }
    }

    public record Definition(int index, NetworkResourceType resource, double occupied, double capacity) {
    }

    public record Change(int index, int fields, double occupied, double capacity) {
    }
}
