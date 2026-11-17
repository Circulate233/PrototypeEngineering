package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKey;
import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.network.resource.NetworkResourcePackets;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Sends fixed definitions when research or queue entries change, then only changed row indices and values.
 */
@SuppressWarnings("ClassCanBeRecord") // Decodes directly from ByteBuf without constructing an intermediate packet.
public final class ResearchStationPacket implements PacketWritable {
    private final boolean reset;
    private final @Nullable Definition definition;
    private final @Nullable List<QueueEntry> queue;
    private final List<MaterialChange> materials;
    private final List<ResourceChange> resources;

    public ResearchStationPacket(boolean reset, @Nullable Definition definition, @Nullable List<QueueEntry> queue,
                                 List<MaterialChange> materials, List<ResourceChange> resources) {
        this.reset = reset;
        this.definition = definition;
        this.queue = queue;
        this.materials = materials;
        this.resources = resources;
    }

    public boolean reset() {
        return reset;
    }

    public @Nullable Definition definition() {
        return definition;
    }

    public @Nullable List<QueueEntry> queue() {
        return queue;
    }

    public List<MaterialChange> materials() {
        return materials;
    }

    public List<ResourceChange> resources() {
        return resources;
    }

    public static final ResearchStationPacket EMPTY = new ResearchStationPacket(true, null, List.of(), List.of(), List.of());
    public static final int FIRST = 1, SECOND = 2, THIRD = 4;

    @SuppressWarnings("unused")
    public ResearchStationPacket(ByteBuf buffer) {
        var data = new PacketBuffer(buffer);
        boolean reset = data.readBoolean();
        Definition definition = null;
        if (reset && data.readBoolean()) {
            long entryId = data.readVarLong();
            var id = data.readResourceLocation();
            int level = data.readVarInt();
            long duration = data.readVarLong();
            int count = data.readVarInt();
            var materials = new ObjectArrayList<AEKey>(count);
            for (int index = 0; index < count; index++) materials.add(AEKey.readKey(data));
            count = data.readVarInt();
            var resources = new ObjectArrayList<Resource>(count);
            for (int index = 0; index < count; index++)
                resources.add(new Resource(NetworkResourcePackets.read(data), data.readDouble()));
            definition = new Definition(entryId, id, level, duration, materials, resources);
        }
        List<QueueEntry> queue = null;
        if (data.readBoolean()) {
            int count = data.readVarInt();
            queue = new ObjectArrayList<>(count);
            for (int index = 0; index < count; index++)
                queue.add(new QueueEntry(data.readVarLong(), data.readResourceLocation(), data.readVarInt()));
        }
        int count = data.readVarInt();
        var materials = new ObjectArrayList<MaterialChange>(count);
        for (int index = 0; index < count; index++) {
            int row = data.readVarInt(), fields = data.readUnsignedByte();
            materials.add(new MaterialChange(row, fields, (fields & FIRST) != 0 ? data.readVarLong() : 0, (fields & SECOND) != 0 ? data.readVarLong() : 0));
        }
        count = data.readVarInt();
        var resources = new ObjectArrayList<ResourceChange>(count);
        for (int index = 0; index < count; index++) {
            int row = data.readVarInt(), fields = data.readUnsignedByte();
            resources.add(new ResourceChange(row, fields, (fields & FIRST) != 0 ? data.readDouble() : 0,
                (fields & SECOND) != 0 ? data.readDouble() : 0, (fields & THIRD) != 0 ? data.readDouble() : 0));
        }
        this.reset = reset;
        this.definition = definition;
        this.queue = queue;
        this.materials = materials;
        this.resources = resources;
    }

    @Override
    public void writeToPacket(ByteBuf buffer) {
        var data = new PacketBuffer(buffer);
        data.writeBoolean(reset);
        if (reset) {
            data.writeBoolean(definition != null);
            if (definition != null) {
                data.writeVarLong(definition.entryId);
                data.writeResourceLocation(definition.id);
                data.writeVarInt(definition.level);
                data.writeVarLong(definition.duration);
                data.writeVarInt(definition.materials.size());
                for (int index1 = 0; index1 < definition.materials.size(); index1++) {
                    var key = definition.materials.get(index1);
                    AEKey.writeKey(data, key);
                }
                data.writeVarInt(definition.resources.size());
                for (int index3 = 0; index3 < definition.resources.size(); index3++) {
                    var resource = definition.resources.get(index3);
                    NetworkResourcePackets.write(data, resource.type);
                    data.writeDouble(resource.total);
                }
            }
        }
        data.writeBoolean(queue != null);
        if (queue != null) {
            data.writeVarInt(queue.size());
            for (int index2 = 0; index2 < queue.size(); index2++) {
                var entry = queue.get(index2);
                data.writeVarLong(entry.entryId);
                data.writeResourceLocation(entry.id);
                data.writeVarInt(entry.level);
            }
        }
        data.writeVarInt(materials.size());
        for (int index4 = 0; index4 < materials.size(); index4++) {
            var change = materials.get(index4);
            data.writeVarInt(change.index);
            data.writeByte(change.fields);
            if ((change.fields & FIRST) != 0) data.writeVarLong(change.available);
            if ((change.fields & SECOND) != 0) data.writeVarLong(change.remaining);
        }
        data.writeVarInt(resources.size());
        for (int index5 = 0; index5 < resources.size(); index5++) {
            var change = resources.get(index5);
            data.writeVarInt(change.index);
            data.writeByte(change.fields);
            if ((change.fields & FIRST) != 0) data.writeDouble(change.occupied);
            if ((change.fields & SECOND) != 0) data.writeDouble(change.capacity);
            if ((change.fields & THIRD) != 0) data.writeDouble(change.accumulated);
        }
    }

    public record Definition(long entryId, ResourceLocation id, int level, long duration,
                             List<AEKey> materials, List<Resource> resources) {
    }

    public record Resource(NetworkResourceType type, double total) {
    }

    public record QueueEntry(long entryId, ResourceLocation id, int level) {
    }

    public record MaterialChange(int index, int fields, long available, long remaining) {
    }

    public record ResourceChange(int index, int fields, double occupied, double capacity, double accumulated) {
    }
}
