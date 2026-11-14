package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKey;
import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.network.resource.NetworkResourcePackets;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.network.PacketBuffer;

import java.util.List;

/**
 * Complete component lists and resource definitions before sending; callers must not mutate them. Publish only on composition changes.
 */
public record ComputerDetailsPacket(List<Component> components) implements PacketWritable {
    public static final ComputerDetailsPacket EMPTY = new ComputerDetailsPacket(List.of());

    @SuppressWarnings("unused") // AE GuiSync invokes the concrete packet type's ByteBuf constructor.
    public ComputerDetailsPacket(ByteBuf data) {
        this(readComponents(new PacketBuffer(data)));
    }

    private static List<Component> readComponents(PacketBuffer data) {
        int count = data.readVarInt();
        var rows = new ObjectArrayList<Component>(count);
        for (int index = 0; index < count; index++)
            rows.add(new Component(AEKey.readKey(data), data.readVarLong(), data.readVarLong(),
                data.readVarLong(), readResources(data), readResources(data)));
        return rows;
    }

    private static Reference2DoubleMap<NetworkResourceType> readResources(PacketBuffer data) {
        int count = data.readVarInt();
        var resources = new Reference2DoubleOpenHashMap<NetworkResourceType>(count);
        for (int index = 0; index < count; index++) resources.put(NetworkResourcePackets.read(data), data.readDouble());
        return resources;
    }

    @Override
    public void writeToPacket(ByteBuf buffer) {
        var data = new PacketBuffer(buffer);
        data.writeVarInt(components.size());
        for (int index1 = 0; index1 < components.size(); index1++) {
            var row = components.get(index1);
            AEKey.writeKey(data, row.item);
            data.writeVarLong(row.count);
            data.writeVarLong(row.concurrency);
            data.writeVarLong(row.energy);
            writeResources(data, row.provided);
            writeResources(data, row.required);
        }
    }

    private static void writeResources(PacketBuffer data, Reference2DoubleMap<NetworkResourceType> resources) {
        data.writeVarInt(resources.size());
        for (var entry : resources.reference2DoubleEntrySet()) {
            NetworkResourcePackets.write(data, entry.getKey());
            data.writeDouble(entry.getDoubleValue());
        }
    }

    public record Component(AEKey item, long count, long concurrency, long energy,
                            Reference2DoubleMap<NetworkResourceType> provided,
                            Reference2DoubleMap<NetworkResourceType> required) {
    }

}
