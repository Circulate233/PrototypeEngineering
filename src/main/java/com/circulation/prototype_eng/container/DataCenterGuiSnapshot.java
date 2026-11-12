package com.circulation.prototype_eng.container;

import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.view.DataCenterPlayerView;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * One bounded database page; team and physical database identity remain part of each action.
 */
public record DataCenterGuiSnapshot(boolean initialized, boolean savePending, String teamId,
                                    Decision viewPermission, Decision downloadPermission,
                                    @Nullable UUID dataCenterId, int level, int capacity, int storedCount,
                                    int total, int offset, ObjectList<Entry> rows, @Nullable Selection selected)
    implements PacketWritable {
    public static final int MAX_ROWS = 6;
    public static final int MAX_PACKET_BYTES = 8192;
    public static final DataCenterGuiSnapshot EMPTY = new DataCenterGuiSnapshot(false, false, "",
        Decision.MISSING_ACTOR_TEAM, Decision.MISSING_ACTOR_TEAM, null, 0, 0, 0, 0, 0, ObjectList.of(), null);
    private static final int MAX_TEXT_LENGTH = 256;
    private static final int VERSION = 1;

    public DataCenterGuiSnapshot {
        requireTeam(teamId);
        Objects.requireNonNull(viewPermission);
        Objects.requireNonNull(downloadPermission);
        var copy = new ObjectArrayList<>(Objects.requireNonNull(rows));
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        boolean selectedPresent = false;
        for (var row : copy) {
            Objects.requireNonNull(row);
            if (!ids.add(row.id())) throw new IllegalArgumentException("Duplicate database GUI row");
            selectedPresent |= row.selection().equals(selected);
            if (dataCenterId == null && row.storedLevel() != 0)
                throw new IllegalArgumentException("Offline database has stored rows");
        }
        rows = ObjectLists.unmodifiable(copy);
        if (total < 0 || offset < 0 || offset % MAX_ROWS != 0 || offset > Math.max(0, total - 1)
            || rows.size() != Math.min(MAX_ROWS, total - offset))
            throw new IllegalArgumentException("Invalid database GUI page");
        if (rows.isEmpty() ? selected != null : !selectedPresent)
            throw new IllegalArgumentException("Database selection is outside its page");
        if (level < 0 || storedCount < 0 || (dataCenterId == null ? level != 0 || capacity != 0 || storedCount != 0
            : capacity < 1 || storedCount > capacity))
            throw new IllegalArgumentException("Invalid database GUI metadata");
        if (!initialized && (savePending || !teamId.isEmpty() || dataCenterId != null || total != 0)) {
            throw new IllegalArgumentException("Uninitialized database GUI contains state");
        }
        if (viewPermission != Decision.ALLOWED && (dataCenterId != null || total != 0)) {
            throw new IllegalArgumentException("Denied database GUI contains research");
        }
    }

    @SuppressWarnings("unused") // AE2 GuiSync uses this exact public PacketWritable constructor.
    public DataCenterGuiSnapshot(ByteBuf data) {
        this(read(data));
    }

    private DataCenterGuiSnapshot(DataCenterGuiSnapshot source) {
        this(source.initialized, source.savePending, source.teamId, source.viewPermission, source.downloadPermission,
            source.dataCenterId, source.level, source.capacity, source.storedCount, source.total, source.offset,
            source.rows, source.selected);
    }

    public static DataCenterGuiSnapshot capture(DataCenterPlayerView view, ResearchRegistry registry, Request request) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(request);
        if (!Objects.requireNonNull(registry).isFrozen())
            throw new IllegalStateException("Database GUI requires frozen definitions");
        var database = view.dataCenter();
        var cloud = view.cloud();
        if (cloud != null && cloud.registry() != registry)
            throw new IllegalArgumentException("Database GUI has another registry");
        if (database != null) {
            for (var entry : database.storedResearch().object2IntEntrySet())
                registry.require(entry.getKey()).stageForLevel(entry.getIntValue());
        }
        var source = new ObjectArrayList<Entry>();
        if (view.viewPermission() == Decision.ALLOWED) {
            for (var definition : registry.sortedById()) {
                int cloudLevel = cloud == null ? 0 : cloud.completedLevel(definition.id());
                int stored = database == null ? 0 : database.storedResearch().getInt(definition.id());
                boolean ordinary = !definition.specialOnly() && definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM;
                if (stored == 0 && (!ordinary || cloudLevel == 0)) continue;
                int target = ordinary ? cloudLevel : 0;
                int displayLevel = Math.max(1, Math.max(target, stored));
                source.add(new Entry(definition.id(), cloudLevel, stored, target, definition.researchTierForLevel(displayLevel)));
            }
        }
        int offset = source.isEmpty() ? 0 : Math.min(request.offset() / MAX_ROWS, (source.size() - 1) / MAX_ROWS) * MAX_ROWS;
        var rows = source.subList(offset, offset + Math.min(MAX_ROWS, source.size() - offset));
        Selection selected = null;
        for (var row : rows) if (row.selection().equals(request.selection())) selected = row.selection();
        if (selected == null && !rows.isEmpty()) selected = rows.getFirst().selection();
        return new DataCenterGuiSnapshot(true, view.savePending(), view.teamId(), view.viewPermission(), view.downloadPermission(),
            database == null ? null : database.dataCenterId(), database == null ? 0 : database.level(),
            database == null ? 0 : database.capacity(), database == null ? 0 : database.storedResearch().size(),
            source.size(), offset, rows, selected);
    }

    private static DataCenterGuiSnapshot read(ByteBuf data) {
        if (data.readableBytes() < Integer.BYTES)
            throw new IllegalArgumentException("Missing database GUI packet length");
        int length = data.readInt();
        if (length < 1 || length > MAX_PACKET_BYTES || length > data.readableBytes())
            throw new IllegalArgumentException("Invalid database GUI packet length");
        var packet = new PacketBuffer(data.readSlice(length));
        try {
            if (packet.readUnsignedByte() != VERSION)
                throw new IllegalArgumentException("Unsupported database GUI packet version");
            boolean initialized = readBoolean(packet), savePending = readBoolean(packet);
            String team = packet.readString(MAX_TEXT_LENGTH);
            var view = readDecision(packet);
            var download = readDecision(packet);
            var database = readBoolean(packet) ? packet.readUniqueId() : null;
            int level = packet.readInt(), capacity = packet.readInt(), stored = packet.readInt(), total = packet.readInt(), offset = packet.readInt();
            int count = packet.readUnsignedByte();
            if (count > MAX_ROWS) throw new IllegalArgumentException("Too many database GUI rows");
            var rows = new ObjectArrayList<Entry>(count);
            for (int i = 0; i < count; i++)
                rows.add(new Entry(readId(packet), packet.readInt(), packet.readInt(), packet.readInt(), packet.readInt()));
            var selected = readBoolean(packet) ? new Selection(readId(packet), packet.readInt()) : null;
            if (packet.isReadable()) throw new IllegalArgumentException("Trailing database GUI packet data");
            return new DataCenterGuiSnapshot(initialized, savePending, team, view, download, database, level, capacity, stored,
                total, offset, rows, selected);
        } catch (IndexOutOfBoundsException failure) {
            throw new IllegalArgumentException("Truncated database GUI packet body", failure);
        }
    }

    private static boolean readBoolean(PacketBuffer packet) {
        int value = packet.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid database GUI boolean");
        return value == 1;
    }

    private static Decision readDecision(PacketBuffer packet) {
        int value = packet.readUnsignedByte();
        if (value >= Decision.VALUES.length) throw new IllegalArgumentException("Invalid database GUI permission");
        return Decision.VALUES[value];
    }

    private static ResourceLocation readId(PacketBuffer packet) {
        return requireId(StrictNbt.requireId(packet.readString(MAX_TEXT_LENGTH)));
    }

    private static ResourceLocation requireId(ResourceLocation id) {
        Objects.requireNonNull(id);
        if (id.toString().length() > MAX_TEXT_LENGTH)
            throw new IllegalArgumentException("Database GUI ID exceeds packet bound");
        return StrictNbt.requireId(id.toString());
    }

    private static void requireTeam(String team) {
        Objects.requireNonNull(team);
        if (team.length() > MAX_TEXT_LENGTH || !team.equals(team.trim()))
            throw new IllegalArgumentException("Invalid database GUI team");
    }

    @Nullable
    public Entry find(Selection selection) {
        for (var row : rows) if (row.selection().equals(selection)) return row;
        return null;
    }

    /**
     * A last-drawn row can never select a replacement database or a new level at the same index.
     */
    public boolean contains(String expectedTeam, @Nullable UUID expectedDataCenter, Selection selection) {
        return teamId.equals(expectedTeam) && Objects.equals(dataCenterId, expectedDataCenter) && find(selection) != null;
    }

    public boolean canDownload(Entry entry) {
        return initialized && !savePending && viewPermission == Decision.ALLOWED && downloadPermission == Decision.ALLOWED
            && dataCenterId != null && entry.targetLevel() > entry.storedLevel() && entry.requiredTier() <= level
            && (entry.storedLevel() > 0 || storedCount < capacity) && Objects.equals(find(entry.selection()), entry);
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        var body = Unpooled.buffer(MAX_PACKET_BYTES, MAX_PACKET_BYTES);
        try {
            var packet = new PacketBuffer(body);
            packet.writeByte(VERSION).writeBoolean(initialized).writeBoolean(savePending);
            packet.writeString(teamId).writeByte(viewPermission.ordinal()).writeByte(downloadPermission.ordinal());
            packet.writeBoolean(dataCenterId != null);
            if (dataCenterId != null) packet.writeUniqueId(dataCenterId);
            packet.writeInt(level).writeInt(capacity).writeInt(storedCount).writeInt(total).writeInt(offset).writeByte(rows.size());
            for (var row : rows)
                packet.writeString(row.id().toString()).writeInt(row.cloudLevel()).writeInt(row.storedLevel())
                      .writeInt(row.targetLevel()).writeInt(row.requiredTier());
            packet.writeBoolean(selected != null);
            if (selected != null) packet.writeString(selected.researchId().toString()).writeInt(selected.targetLevel());
            data.writeInt(body.readableBytes()).writeBytes(body, body.readerIndex(), body.readableBytes());
        } finally {
            body.release();
        }
    }

    public record Selection(ResourceLocation researchId, int targetLevel) {
        public Selection {
            requireId(researchId);
            if (targetLevel < 0) throw new IllegalArgumentException("Negative database target level");
        }
    }

    public record Request(int offset, @Nullable Selection selection) {
        public Request {
            if (offset < 0) throw new IllegalArgumentException("Negative database page offset");
        }
    }

    public record Entry(ResourceLocation id, int cloudLevel, int storedLevel, int targetLevel, int requiredTier) {
        public Entry {
            requireId(id);
            if (storedLevel < 0 || targetLevel < 0 || targetLevel > cloudLevel || requiredTier < 0
                || cloudLevel == 0 && storedLevel == 0) throw new IllegalArgumentException("Invalid database GUI row");
        }

        public Selection selection() {
            return new Selection(id, targetLevel);
        }
    }
}
