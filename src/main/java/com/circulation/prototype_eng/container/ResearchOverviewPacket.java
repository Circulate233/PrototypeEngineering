package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.network.resource.NetworkResourcePackets;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.BiConsumer;

/**
 * Definitions are sent once. Subsequent updates contain only changed research rows and sections.
 */
@SuppressWarnings({"ClassCanBeRecord", "DataFlowIssue"})
public final class ResearchOverviewPacket implements PacketWritable {
    public static final ResearchOverviewPacket EMPTY = new ResearchOverviewPacket(true, List.of(), List.of(), List.of(), List.of(), true, null, Environment.EMPTY);
    private final boolean reset;
    private final @Nullable List<Definition> definitions;
    private final List<State> states;
    private final @Nullable List<Queue> queue;
    private final @Nullable List<Database> databases;
    private final boolean currentChanged;
    private final @Nullable Current current;
    private final @Nullable Environment environment;
    private final @Nullable Current beforeCurrent;
    private final @Nullable CurrentDelta currentDelta;

    public ResearchOverviewPacket(boolean reset, @Nullable List<Definition> definitions, List<State> states,
                                  @Nullable List<Queue> queue, @Nullable List<Database> databases,
                                  boolean currentChanged, @Nullable Current current, @Nullable Environment environment) {
        this.reset = reset;
        this.definitions = definitions;
        this.states = states;
        this.queue = queue;
        this.databases = databases;
        this.currentChanged = currentChanged;
        this.current = current;
        this.environment = environment;
        beforeCurrent = null;
        currentDelta = null;
    }

    public ResearchOverviewPacket(boolean reset, @Nullable List<Definition> definitions, List<State> states,
                                  @Nullable List<Queue> queue, @Nullable List<Database> databases,
                                  boolean currentChanged, @Nullable Current current, @Nullable Environment environment, @Nullable Current beforeCurrent) {
        this.reset = reset;
        this.definitions = definitions;
        this.states = states;
        this.queue = queue;
        this.databases = databases;
        this.currentChanged = currentChanged;
        this.current = current;
        this.environment = environment;
        this.beforeCurrent = beforeCurrent;
        currentDelta = null;
    }

    public boolean reset() {
        return reset;
    }

    public @Nullable List<Definition> definitions() {
        return definitions;
    }

    public List<State> states() {
        return states;
    }

    public @Nullable List<Queue> queue() {
        return queue;
    }

    public @Nullable List<Database> databases() {
        return databases;
    }

    public boolean currentChanged() {
        return currentChanged;
    }

    public @Nullable Current current() {
        return current;
    }

    public @Nullable Current applyCurrent(@Nullable Current baseline) {
        if (currentDelta == null) return current;
        return new Current(baseline.id(), baseline.entryId(), baseline.targetLevel(), currentDelta.elapsed(), baseline.duration(), currentDelta.working(),
            baseline.manual(), currentDelta.contributorName(), currentDelta.contributor(), applyMaterials(baseline), applyResources(baseline));
    }

    private List<MaterialProgress> applyMaterials(Current baseline) {
        if (currentDelta.materials().isEmpty()) return baseline.materials();
        var materials = new ObjectArrayList<>(baseline.materials());
        for (int index = 0; index < currentDelta.materials().size(); index++) {
            var change = currentDelta.materials().get(index);
            var previous = materials.get(change.index());
            materials.set(change.index(), new MaterialProgress(previous.key(), (change.fields() & 1) != 0 ? change.available() : previous.available(),
                (change.fields() & 2) != 0 ? change.remaining() : previous.remaining(), previous.total()));
        }
        return materials;
    }

    private List<ResourceProgress> applyResources(Current baseline) {
        if (currentDelta.resources().isEmpty()) return baseline.resources();
        var resources = new ObjectArrayList<>(baseline.resources());
        for (int index = 0; index < currentDelta.resources().size(); index++) {
            var change = currentDelta.resources().get(index);
            var previous = resources.get(change.index());
            resources.set(change.index(), new ResourceProgress(previous.type(), (change.fields() & 1) != 0 ? change.occupied() : previous.occupied(),
                (change.fields() & 2) != 0 ? change.capacity() : previous.capacity(), (change.fields() & 4) != 0 ? change.accumulated() : previous.accumulated(), previous.total()));
        }
        return resources;
    }

    public @Nullable Environment environment() {
        return environment;
    }

    @SuppressWarnings("unused")
    public ResearchOverviewPacket(ByteBuf buffer) {
        var data = new PacketBuffer(buffer);
        reset = data.readBoolean();
        definitions = data.readBoolean() ? readList(data, ResearchOverviewPacket::readDefinition) : null;
        states = readList(data, ResearchOverviewPacket::readState);
        queue = data.readBoolean() ? readList(data, value -> new Queue(value.readVarLong(), value.readResourceLocation(), value.readVarInt())) : null;
        databases = data.readBoolean() ? readList(data, ResearchOverviewPacket::readDatabase) : null;
        currentChanged = data.readBoolean();
        int mode = currentChanged ? data.readUnsignedByte() : 0;
        current = mode == 1 ? readCurrent(data) : null;
        currentDelta = mode == 2 ? readCurrentDelta(data) : null;
        beforeCurrent = null;
        environment = data.readBoolean() ? readEnvironment(data) : null;
    }

    @Override
    public void writeToPacket(ByteBuf buffer) {
        var data = new PacketBuffer(buffer);
        data.writeBoolean(reset);
        data.writeBoolean(definitions != null);
        if (definitions != null) writeList(data, definitions, ResearchOverviewPacket::writeDefinition);
        writeList(data, states, ResearchOverviewPacket::writeState);
        data.writeBoolean(queue != null);
        if (queue != null) writeList(data, queue, (target, row) -> {
            target.writeVarLong(row.entryId());
            target.writeResourceLocation(row.id());
            target.writeVarInt(row.targetLevel());
        });
        data.writeBoolean(databases != null);
        if (databases != null) writeList(data, databases, ResearchOverviewPacket::writeDatabase);
        data.writeBoolean(currentChanged);
        if (currentChanged) {
            boolean delta = !reset && current != null && beforeCurrent != null && current.entryId() == beforeCurrent.entryId()
                && current.targetLevel() == beforeCurrent.targetLevel() && current.materials().size() == beforeCurrent.materials().size()
                && current.resources().size() == beforeCurrent.resources().size();
            data.writeByte(current == null ? 0 : delta ? 2 : 1);
            if (delta) writeCurrentDelta(data, beforeCurrent, current);
            else if (current != null) writeCurrent(data, current);
        }
        data.writeBoolean(environment != null);
        if (environment != null) writeEnvironment(data, environment);
    }

    public record Required(ResourceLocation id, int level) {
    }

    public record Material(AEKey key, long total) {
    }

    public record Resource(NetworkResourceType type, double total, double slope) {
        public double totalAt(int firstLevel, int level) {
            return total + slope * ((long) level - firstLevel);
        }
    }

    public record RewardGroup(ResourceLocation id, List<GenericStack> items) {
    }

    public record Rewards(int level, List<RewardGroup> groups) {
    }

    public record Stage(int firstLevel, int lastLevel, int tier, long duration, List<Required> prerequisites,
                        List<Required> mutex, List<Material> materials, List<Resource> resources,
                        List<Rewards> rewards) {
    }

    public record Definition(ResourceLocation id, int maxLevel, boolean specialOnly,
                             ResearchDownloadDeliveryPolicy deliveryPolicy,
                             List<Stage> stages) {
    }

    public record MaterialProgress(AEKey key, long available, long remaining, long total) {
    }

    public record ResourceProgress(NetworkResourceType type, double occupied, double capacity, double accumulated,
                                   double total) {
    }

    public record Progress(int targetLevel, long elapsed, List<MaterialProgress> materials,
                           List<ResourceProgress> resources) {
    }

    public record Claim(int level, RewardClaim.Status status, @Nullable ResourceLocation selectedGroupId) {
    }

    public record State(ResourceLocation id, int personalLevel, int teamLevel, int startableTarget,
                        int downloadedLevel, String downloadedTo, boolean canStart, boolean canDownload,
                        boolean canForget, boolean selectable, int processableLevel,
                        @Nullable Progress progress, List<Claim> claims, int otherMemberLevel,
                        Availability availability) {
        public static final State EMPTY = new State(new ResourceLocation("prototype_eng", "empty"), 0, 0, 0, 0, "", false, false, false, false, 0, null, List.of(), 0, Availability.EMPTY);
    }

    public record Availability(ResearchPermissionPolicy.Decision start, ResearchPermissionPolicy.Decision download,
                               ResearchPermissionPolicy.Decision forget, ResearchPermissionPolicy.Decision queue) {
        public static final Availability EMPTY = new Availability(ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM,
            ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM, ResearchPermissionPolicy.Decision.TARGET_PLAYER_MISMATCH,
            ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
    }

    public record Entry(Definition definition, State state) {
        public ResourceLocation id() {
            return definition.id();
        }

        public int maxLevel() {
            return definition.maxLevel();
        }

        public boolean specialOnly() {
            return definition.specialOnly();
        }

        public ResearchDownloadDeliveryPolicy deliveryPolicy() {
            return definition.deliveryPolicy();
        }

        public List<Stage> stages() {
            return definition.stages();
        }

        public int personalLevel() {
            return state.personalLevel();
        }

        public int teamLevel() {
            return state.teamLevel();
        }


        public int downloadedLevel() {
            return state.downloadedLevel();
        }

        public String downloadedTo() {
            return state.downloadedTo();
        }

        public boolean canStart() {
            return state.canStart();
        }

        public boolean canDownload() {
            return state.canDownload();
        }

        public boolean canForget() {
            return state.canForget();
        }

        public boolean selectable() {
            return state.selectable();
        }

        public int processableLevel() {
            return state.processableLevel();
        }

        public @Nullable Progress progress() {
            return state.progress();
        }

        public List<Claim> claims() {
            return state.claims();
        }

        public int otherMemberLevel() {
            return state.otherMemberLevel();
        }

        public Availability availability() {
            return state.availability();
        }

        public Stage stage(int level) {
            for (int index = 0; index < stages().size(); index++) {
                var stage = stages().get(index);
                if (stage.firstLevel() <= level && level <= stage.lastLevel()) return stage;
            }
            throw new IllegalArgumentException("Level is outside research stages");
        }
    }

    public record Queue(long entryId, ResourceLocation id, int targetLevel) {
    }

    public record Stored(ResourceLocation id, int level) {
    }

    public record Database(UUID id, String name, int tier, int capacity, List<Stored> stored) {
        public int remaining() {
            return capacity - stored.size();
        }

        public int level(ResourceLocation research) {
            for (int index = 0; index < stored.size(); index++)
                if (stored.get(index).id().equals(research)) return stored.get(index).level();
            return 0;
        }
    }

    public record Current(ResourceLocation id, long entryId, int targetLevel, long elapsed, long duration,
                          int workingStations, boolean manual, String contributorName, @Nullable UUID contributor,
                          List<MaterialProgress> materials, List<ResourceProgress> resources) {
    }

    private record MaterialChange(int index, int fields, long available, long remaining) {
    }

    private record ResourceChange(int index, int fields, double occupied, double capacity, double accumulated) {
    }

    private record CurrentDelta(long elapsed, int working, String contributorName, @Nullable UUID contributor,
                                List<MaterialChange> materials, List<ResourceChange> resources) {
    }

    public record Environment(String teamId, String teamName, int centerLevel, int stationLevel,
                              @Nullable UUID activeDatabase, boolean canManage, boolean savePending,
                              boolean editable, boolean visible, int databaseLevel, String databaseName,
                              int databaseStored, int databaseCapacity, @Nullable UUID playerId) {
        public static final Environment EMPTY = new Environment("", "", 0, 0, null, false, false, false, false, 0, "", 0, 0, null);
    }

    private static Definition readDefinition(PacketBuffer data) {
        return new Definition(data.readResourceLocation(), data.readVarInt(), data.readBoolean(),
            ResearchDownloadDeliveryPolicy.values()[data.readUnsignedByte()], readList(data, ResearchOverviewPacket::readStage));
    }

    private static void writeDefinition(PacketBuffer data, Definition value) {
        data.writeResourceLocation(value.id());
        data.writeVarInt(value.maxLevel());
        data.writeBoolean(value.specialOnly());
        data.writeByte(value.deliveryPolicy().ordinal());
        writeList(data, value.stages(), ResearchOverviewPacket::writeStage);
    }

    private static Stage readStage(PacketBuffer data) {
        return new Stage(data.readVarInt(), data.readVarInt(), data.readVarInt(), data.readVarLong(),
            readList(data, value -> new Required(value.readResourceLocation(), value.readVarInt())),
            readList(data, value -> new Required(value.readResourceLocation(), value.readVarInt())),
            readList(data, value -> new Material(AEKey.readKey(value), value.readVarLong())),
            readList(data, value -> new Resource(NetworkResourcePackets.read(value), value.readDouble(), value.readDouble())),
            readList(data, value -> new Rewards(value.readVarInt(), readList(value, group -> new RewardGroup(group.readResourceLocation(),
                readList(group, item -> new GenericStack(AEKey.readKey(item), item.readVarLong())))))));
    }

    private static void writeStage(PacketBuffer data, Stage value) {
        data.writeVarInt(value.firstLevel());
        data.writeVarInt(value.lastLevel());
        data.writeVarInt(value.tier());
        data.writeVarLong(value.duration());
        writeList(data, value.prerequisites(), ResearchOverviewPacket::writeRequired);
        writeList(data, value.mutex(), ResearchOverviewPacket::writeRequired);
        writeList(data, value.materials(), (target, item) -> {
            AEKey.writeKey(target, item.key());
            target.writeVarLong(item.total());
        });
        writeList(data, value.resources(), (target, resource) -> {
            NetworkResourcePackets.write(target, resource.type());
            target.writeDouble(resource.total());
            target.writeDouble(resource.slope());
        });
        writeList(data, value.rewards(), (target, reward) -> {
            target.writeVarInt(reward.level());
            writeList(target, reward.groups(), (groupTarget, group) -> {
                groupTarget.writeResourceLocation(group.id());
                writeList(groupTarget, group.items(), (itemTarget, item) -> {
                    AEKey.writeKey(itemTarget, item.what());
                    itemTarget.writeVarLong(item.amount());
                });
            });
        });
    }

    private static void writeRequired(PacketBuffer data, Required value) {
        data.writeResourceLocation(value.id());
        data.writeVarInt(value.level());
    }

    private static State readState(PacketBuffer data) {
        return new State(data.readResourceLocation(), data.readVarInt(), data.readVarInt(), data.readVarInt(),
            data.readVarInt(), data.readString(32767), data.readBoolean(), data.readBoolean(), data.readBoolean(), data.readBoolean(),
            data.readVarInt(), data.readBoolean() ? readProgress(data) : null,
            readList(data, value -> new Claim(value.readVarInt(), RewardClaim.Status.values()[value.readUnsignedByte()], value.readBoolean() ? value.readResourceLocation() : null)), data.readVarInt(),
            new Availability(ResearchPermissionPolicy.Decision.values()[data.readUnsignedByte()], ResearchPermissionPolicy.Decision.values()[data.readUnsignedByte()],
                ResearchPermissionPolicy.Decision.values()[data.readUnsignedByte()], ResearchPermissionPolicy.Decision.values()[data.readUnsignedByte()]));
    }

    private static void writeState(PacketBuffer data, State value) {
        data.writeResourceLocation(value.id());
        data.writeVarInt(value.personalLevel());
        data.writeVarInt(value.teamLevel());
        data.writeVarInt(value.startableTarget());
        data.writeVarInt(value.downloadedLevel());
        data.writeString(value.downloadedTo());
        data.writeBoolean(value.canStart());
        data.writeBoolean(value.canDownload());
        data.writeBoolean(value.canForget());
        data.writeBoolean(value.selectable());
        data.writeVarInt(value.processableLevel());
        data.writeBoolean(value.progress() != null);
        if (value.progress() != null) writeProgress(data, value.progress());
        writeList(data, value.claims(), (target, claim) -> {
            target.writeVarInt(claim.level());
            target.writeByte(claim.status().ordinal());
            target.writeBoolean(claim.selectedGroupId() != null);
            if (claim.selectedGroupId() != null) target.writeResourceLocation(claim.selectedGroupId());
        });
        data.writeVarInt(value.otherMemberLevel());
        data.writeByte(value.availability().start().ordinal());
        data.writeByte(value.availability().download().ordinal());
        data.writeByte(value.availability().forget().ordinal());
        data.writeByte(value.availability().queue().ordinal());
    }

    private static Progress readProgress(PacketBuffer data) {
        return new Progress(data.readVarInt(), data.readVarLong(), readList(data, ResearchOverviewPacket::readMaterialProgress), readList(data, ResearchOverviewPacket::readResourceProgress));
    }

    private static void writeProgress(PacketBuffer data, Progress value) {
        data.writeVarInt(value.targetLevel());
        data.writeVarLong(value.elapsed());
        writeList(data, value.materials(), ResearchOverviewPacket::writeMaterialProgress);
        writeList(data, value.resources(), ResearchOverviewPacket::writeResourceProgress);
    }

    private static MaterialProgress readMaterialProgress(PacketBuffer data) {
        return new MaterialProgress(AEKey.readKey(data), data.readVarLong(), data.readVarLong(), data.readVarLong());
    }

    private static void writeMaterialProgress(PacketBuffer data, MaterialProgress value) {
        AEKey.writeKey(data, value.key());
        data.writeVarLong(value.available());
        data.writeVarLong(value.remaining());
        data.writeVarLong(value.total());
    }

    private static ResourceProgress readResourceProgress(PacketBuffer data) {
        return new ResourceProgress(NetworkResourcePackets.read(data), data.readDouble(), data.readDouble(), data.readDouble(), data.readDouble());
    }

    private static void writeResourceProgress(PacketBuffer data, ResourceProgress value) {
        NetworkResourcePackets.write(data, value.type());
        data.writeDouble(value.occupied());
        data.writeDouble(value.capacity());
        data.writeDouble(value.accumulated());
        data.writeDouble(value.total());
    }

    private static Database readDatabase(PacketBuffer data) {
        return new Database(data.readUniqueId(), data.readString(32767), data.readVarInt(), data.readVarInt(), readList(data, value -> new Stored(value.readResourceLocation(), value.readVarInt())));
    }

    private static void writeDatabase(PacketBuffer data, Database value) {
        data.writeUniqueId(value.id());
        data.writeString(value.name());
        data.writeVarInt(value.tier());
        data.writeVarInt(value.capacity());
        writeList(data, value.stored(), (target, row) -> {
            target.writeResourceLocation(row.id());
            target.writeVarInt(row.level());
        });
    }

    private static Current readCurrent(PacketBuffer data) {
        return new Current(data.readResourceLocation(), data.readVarLong(), data.readVarInt(), data.readVarLong(), data.readVarLong(), data.readVarInt(),
            data.readBoolean(), data.readString(32767), data.readBoolean() ? data.readUniqueId() : null,
            readList(data, ResearchOverviewPacket::readMaterialProgress), readList(data, ResearchOverviewPacket::readResourceProgress));
    }

    private static void writeCurrent(PacketBuffer data, Current value) {
        data.writeResourceLocation(value.id());
        data.writeVarLong(value.entryId());
        data.writeVarInt(value.targetLevel());
        data.writeVarLong(value.elapsed());
        data.writeVarLong(value.duration());
        data.writeVarInt(value.workingStations());
        data.writeBoolean(value.manual());
        data.writeString(value.contributorName());
        data.writeBoolean(value.contributor() != null);
        if (value.contributor() != null) data.writeUniqueId(value.contributor());
        writeList(data, value.materials(), ResearchOverviewPacket::writeMaterialProgress);
        writeList(data, value.resources(), ResearchOverviewPacket::writeResourceProgress);
    }

    private static CurrentDelta readCurrentDelta(PacketBuffer data) {
        long elapsed = data.readVarLong();
        int working = data.readVarInt();
        String name = data.readString(32767);
        UUID contributor = data.readBoolean() ? data.readUniqueId() : null;
        var materials = readList(data, value -> {
            int index = value.readVarInt(), fields = value.readUnsignedByte();
            return new MaterialChange(index, fields, (fields & 1) != 0 ? value.readVarLong() : 0, (fields & 2) != 0 ? value.readVarLong() : 0);
        });
        var resources = readList(data, value -> {
            int index = value.readVarInt(), fields = value.readUnsignedByte();
            return new ResourceChange(index, fields, (fields & 1) != 0 ? value.readDouble() : 0, (fields & 2) != 0 ? value.readDouble() : 0, (fields & 4) != 0 ? value.readDouble() : 0);
        });
        return new CurrentDelta(elapsed, working, name, contributor, materials, resources);
    }

    private static void writeCurrentDelta(PacketBuffer data, Current before, Current current) {
        data.writeVarLong(current.elapsed());
        data.writeVarInt(current.workingStations());
        data.writeString(current.contributorName());
        data.writeBoolean(current.contributor() != null);
        if (current.contributor() != null) data.writeUniqueId(current.contributor());
        var materials = new ObjectArrayList<MaterialChange>();
        for (int index = 0; index < current.materials().size(); index++) {
            var old = before.materials().get(index);
            var value = current.materials().get(index);
            int fields = (old.available() != value.available() ? 1 : 0) | (old.remaining() != value.remaining() ? 2 : 0);
            if (fields != 0) materials.add(new MaterialChange(index, fields, value.available(), value.remaining()));
        }
        writeList(data, materials, (target, change) -> {
            target.writeVarInt(change.index());
            target.writeByte(change.fields());
            if ((change.fields() & 1) != 0) target.writeVarLong(change.available());
            if ((change.fields() & 2) != 0) target.writeVarLong(change.remaining());
        });
        var resources = new ObjectArrayList<ResourceChange>();
        for (int index = 0; index < current.resources().size(); index++) {
            var old = before.resources().get(index);
            var value = current.resources().get(index);
            int fields = (old.occupied() != value.occupied() ? 1 : 0) | (old.capacity() != value.capacity() ? 2 : 0) | (old.accumulated() != value.accumulated() ? 4 : 0);
            if (fields != 0)
                resources.add(new ResourceChange(index, fields, value.occupied(), value.capacity(), value.accumulated()));
        }
        writeList(data, resources, (target, change) -> {
            target.writeVarInt(change.index());
            target.writeByte(change.fields());
            if ((change.fields() & 1) != 0) target.writeDouble(change.occupied());
            if ((change.fields() & 2) != 0) target.writeDouble(change.capacity());
            if ((change.fields() & 4) != 0) target.writeDouble(change.accumulated());
        });
    }

    private static Environment readEnvironment(PacketBuffer data) {
        return new Environment(data.readString(32767), data.readString(32767), data.readVarInt(), data.readVarInt(), data.readBoolean() ? data.readUniqueId() : null,
            data.readBoolean(), data.readBoolean(), data.readBoolean(), data.readBoolean(), data.readVarInt(), data.readString(32767), data.readVarInt(), data.readVarInt(), data.readBoolean() ? data.readUniqueId() : null);
    }

    private static void writeEnvironment(PacketBuffer data, Environment value) {
        data.writeString(value.teamId());
        data.writeString(value.teamName());
        data.writeVarInt(value.centerLevel());
        data.writeVarInt(value.stationLevel());
        data.writeBoolean(value.activeDatabase() != null);
        if (value.activeDatabase() != null) data.writeUniqueId(value.activeDatabase());
        data.writeBoolean(value.canManage());
        data.writeBoolean(value.savePending());
        data.writeBoolean(value.editable());
        data.writeBoolean(value.visible());
        data.writeVarInt(value.databaseLevel());
        data.writeString(value.databaseName());
        data.writeVarInt(value.databaseStored());
        data.writeVarInt(value.databaseCapacity());
        data.writeBoolean(value.playerId() != null);
        if (value.playerId() != null) data.writeUniqueId(value.playerId());
    }

    private static <T> List<T> readList(PacketBuffer data, Function<PacketBuffer, T> reader) {
        int count = data.readVarInt();
        var values = new ObjectArrayList<T>(count);
        for (int index = 0; index < count; index++) values.add(reader.apply(data));
        return values;
    }

    private static <T> void writeList(PacketBuffer data, List<T> values, BiConsumer<PacketBuffer, T> writer) {
        data.writeVarInt(values.size());
        for (int index = 0; index < values.size(); index++) writer.accept(data, values.get(index));
    }
}
