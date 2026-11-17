package com.circulation.prototype_eng.container;

import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import java.util.Comparator;
import java.util.List;

/**
 * Completed entries are borrowed read-only. Lists must support constant-time indexed access.
 */
public record DataCenterResearchPacket(List<Entry> entries) implements PacketWritable {
    public static final DataCenterResearchPacket EMPTY = new DataCenterResearchPacket(List.of());

    @SuppressWarnings("unused")
    public DataCenterResearchPacket(ByteBuf buffer) {
        this(readEntries(new PacketBuffer(buffer)));
    }

    private static List<Entry> readEntries(PacketBuffer buffer) {
        int count = buffer.readVarInt();
        var entries = new ObjectArrayList<Entry>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(buffer.readResourceLocation(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
        }
        return entries;
    }

    public static DataCenterResearchPacket capture(Object2IntMap<ResourceLocation> stored) {
        if (stored.isEmpty()) return EMPTY;
        var entries = new ObjectArrayList<Entry>(stored.size());
        for (var entry : stored.object2IntEntrySet()) entries.add(entry(entry.getKey(), entry.getIntValue()));
        entries.sort(Comparator.comparing(Entry::id));
        return new DataCenterResearchPacket(entries);
    }

    public static Entry entry(ResourceLocation id, int level) {
        var definition = ResearchRegistry.INSTANCE.require(id);
        return new Entry(id, level, definition.maxLevel(), definition.researchTierForLevel(level));
    }

    @Override
    public void writeToPacket(ByteBuf buffer) {
        var data = new PacketBuffer(buffer);
        data.writeVarInt(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            data.writeResourceLocation(entry.id);
            data.writeVarInt(entry.level);
            data.writeVarInt(entry.maxLevel);
            data.writeVarInt(entry.tier);
        }
    }

    public record Entry(ResourceLocation id, int level, int maxLevel, int tier) {
    }
}
