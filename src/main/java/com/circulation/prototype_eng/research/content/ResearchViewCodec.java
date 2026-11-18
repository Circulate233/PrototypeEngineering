package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.content.ResearchPage;
import com.circulation.prototype_eng.network.ResearchPayload;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public final class ResearchViewCodec {
    private static final Gson JSON = new Gson();
    private ResearchViewCodec() {
    }

    public static void writeLayout(PacketBuffer buffer, ResearchOverviewLayout value) {
        buffer.writeVarInt(value.pages().size());
        for (var entry : value.pages()) {
            var page = entry.page();
            buffer.writeString(entry.id());
            buffer.writeInt(page.priority());
            buffer.writeBoolean(page.manual());
            writeNullableText(buffer, page.title());
            var members = page.researches();
            if (members == null) throw new IllegalArgumentException("Research layout page lacks resolved members: " + entry.id());
            buffer.writeVarInt(members.size());
            for (var id : members) buffer.writeResourceLocation(id);
            writePositions(buffer, page.positions());
        }
        buffer.writeVarInt(value.nodes().size());
        for (var entry : value.nodes().entrySet()) {
            buffer.writeResourceLocation(entry.getKey());
            ResearchPayload.writeText(buffer, entry.getValue().title());
            var icon = entry.getValue().icon();
            writeNullableText(buffer, icon == null ? null : icon.toString());
        }
    }

    public static ResearchOverviewLayout readLayout(PacketBuffer buffer) {
        int pageCount = count(buffer);
        var pages = new ObjectArrayList<ResearchOverviewLayout.Page>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            var id = buffer.readString(32767);
            int priority = buffer.readInt();
            boolean manual = buffer.readBoolean();
            var title = readNullableText(buffer);
            int memberCount = count(buffer);
            var members = new ObjectArrayList<ResourceLocation>(memberCount);
            for (int member = 0; member < memberCount; member++) members.add(buffer.readResourceLocation());
            pages.add(new ResearchOverviewLayout.Page(id, new ResearchPage(priority, manual, title, List.copyOf(members), readPositions(buffer))));
        }
        int nodeCount = count(buffer);
        var nodes = new Object2ObjectOpenHashMap<ResourceLocation, ResearchOverviewLayout.Node>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            var id = buffer.readResourceLocation();
            var title = ResearchPayload.readText(buffer);
            var rawIcon = readNullableText(buffer);
            JsonObject icon = rawIcon == null ? null : JSON.fromJson(rawIcon, JsonObject.class);
            if (nodes.put(id, new ResearchOverviewLayout.Node(title, icon)) != null)
                throw new IllegalArgumentException("Duplicate research node: " + id);
        }
        return new ResearchOverviewLayout(List.copyOf(pages), Map.copyOf(nodes));
    }

    public static void writePositions(PacketBuffer buffer, Map<String, ResearchPage.Position> positions) {
        buffer.writeVarInt(positions.size());
        for (var entry : positions.entrySet()) {
            buffer.writeString(entry.getKey());
            buffer.writeInt(entry.getValue().x());
            buffer.writeInt(entry.getValue().y());
        }
    }

    public static Object2ObjectOpenHashMap<String, ResearchPage.Position> readPositions(PacketBuffer buffer) {
        int size = count(buffer);
        var positions = new Object2ObjectOpenHashMap<String, ResearchPage.Position>(size);
        for (int i = 0; i < size; i++) {
            var id = buffer.readString(32767);
            if (positions.put(id, new ResearchPage.Position(buffer.readInt(), buffer.readInt())) != null)
                throw new IllegalArgumentException("Duplicate research position: " + id);
        }
        return positions;
    }

    public static void writeNullableText(PacketBuffer buffer, @Nullable String text) {
        buffer.writeBoolean(text != null);
        if (text != null) ResearchPayload.writeText(buffer, text);
    }

    public static @Nullable String readNullableText(PacketBuffer buffer) {
        return buffer.readBoolean() ? ResearchPayload.readText(buffer) : null;
    }

    private static int count(PacketBuffer buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > buffer.readableBytes()) throw new IllegalArgumentException("Invalid research catalog count");
        return count;
    }
}
