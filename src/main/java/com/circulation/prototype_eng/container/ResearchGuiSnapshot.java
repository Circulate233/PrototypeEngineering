package com.circulation.prototype_eng.container;

import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded AE2 GuiSync display pages, detached from research owners and recovery payloads.
 */
public record ResearchGuiSnapshot(boolean initialized, boolean savePending, ResearchPlayerView.TeamStatus teamStatus,
                                  Decision personalPermission, Decision teamViewPermission, Decision queuePermission,
                                  Decision startPermission, Decision blacklistPermission, Tab tab, int total,
                                  int offset,
                                  ObjectList<Entry> rows, @Nullable Selection selected,
                                  int detailTotal, int detailOffset,
                                  ObjectList<Detail> details, Decision forgetPermission,
                                  Decision adminPermission) implements PacketWritable {
    public static final int MAX_ROWS = 6;
    public static final int MAX_DETAILS = 6;
    public static final int MAX_PACKET_BYTES = 16384;
    public static final ResearchGuiSnapshot EMPTY = new ResearchGuiSnapshot(false, false, ResearchPlayerView.TeamStatus.UNAVAILABLE,
        Decision.MISSING_ACTOR_TEAM, Decision.MISSING_ACTOR_TEAM, Decision.MISSING_ACTOR_TEAM,
        Decision.MISSING_ACTOR_TEAM, Decision.MISSING_ACTOR_TEAM, Tab.RESEARCH, 0, 0, ObjectList.of(), null, 0, 0, ObjectList.of());
    private static final int MAX_ID_LENGTH = 256;
    private static final int VERSION = 2;
    private static final Comparator<ResourceLocation> ID_ORDER = Comparator.comparing(ResourceLocation::toString);

    public ResearchGuiSnapshot {
        Objects.requireNonNull(teamStatus);
        Objects.requireNonNull(personalPermission);
        Objects.requireNonNull(forgetPermission);
        Objects.requireNonNull(adminPermission);
        Objects.requireNonNull(teamViewPermission);
        Objects.requireNonNull(queuePermission);
        Objects.requireNonNull(startPermission);
        Objects.requireNonNull(blacklistPermission);
        Objects.requireNonNull(tab);
        rows = frozen(rows);
        details = frozen(details);
        requirePage(total, offset, rows.size(), MAX_ROWS);
        requirePage(detailTotal, detailOffset, details.size(), MAX_DETAILS);
        if (!initialized && (total != 0 || detailTotal != 0 || selected != null || savePending)) {
            throw new IllegalArgumentException("Uninitialized research page contains display state");
        }
        var identities = new ObjectOpenHashSet<Selection>();
        boolean found = false;
        for (var row : rows) {
            var identity = row.selection();
            if (!identities.add(identity)) throw new IllegalArgumentException("Duplicate research GUI row");
            found |= identity.equals(selected);
            if (tab == Tab.QUEUE ? row.entryId() < 1 || row.targetLevel() < 1 || row.claimStatus() != null
                : row.entryId() != 0 || tab == Tab.REWARDS && (row.targetLevel() < 1 || row.claimStatus() == null)
                || tab == Tab.RESEARCH && row.claimStatus() != null) {
                throw new IllegalArgumentException("Research row identity does not match its tab");
            }
            if (personalPermission != Decision.ALLOWED && (row.personalLevel() != 0 || row.claimStatus() != null)) {
                throw new IllegalArgumentException("Denied personal research data");
            }
            if (teamViewPermission != Decision.ALLOWED && (row.sharedLevel() != 0 || row.blacklisted()
                || tab == Tab.RESEARCH && row.targetLevel() != 0))
                throw new IllegalArgumentException("Denied team research data");
        }
        if (rows.isEmpty() ? selected != null || detailTotal != 0 : !found)
            throw new IllegalArgumentException("Research selection is outside its page");
        if (tab == Tab.QUEUE && queuePermission != Decision.ALLOWED && total != 0
            || tab == Tab.REWARDS && personalPermission != Decision.ALLOWED && total != 0) {
            throw new IllegalArgumentException("Denied research tab contains rows");
        }
        for (var detail : details) {
            if ((tab == Tab.REWARDS) != (detail.kind() == Kind.REWARD_CHOICE))
                throw new IllegalArgumentException("Research detail does not match its tab");
        }
    }

    /** Older Java display callers cannot infer operation permissions from view permission. */
    public ResearchGuiSnapshot(boolean initialized, boolean savePending, ResearchPlayerView.TeamStatus teamStatus,
                               Decision personalPermission, Decision teamViewPermission, Decision queuePermission,
                               Decision startPermission, Decision blacklistPermission, Tab tab, int total, int offset,
                               ObjectList<Entry> rows, @Nullable Selection selected, int detailTotal, int detailOffset,
                               ObjectList<Detail> details) {
        this(initialized, savePending, teamStatus, personalPermission, teamViewPermission, queuePermission,
            startPermission, blacklistPermission, tab, total, offset, rows, selected, detailTotal, detailOffset, details,
            Decision.TARGET_PLAYER_MISMATCH, Decision.NOT_SERVER_OPERATOR);
    }

    @SuppressWarnings("unused") // AE2 GuiSync constructs PacketWritable fields with this exact signature.
    public ResearchGuiSnapshot(ByteBuf data) {
        this(read(data));
    }

    private ResearchGuiSnapshot(ResearchGuiSnapshot decoded) {
        this(decoded.initialized, decoded.savePending, decoded.teamStatus, decoded.personalPermission, decoded.teamViewPermission,
            decoded.queuePermission, decoded.startPermission, decoded.blacklistPermission, decoded.tab, decoded.total, decoded.offset,
            decoded.rows, decoded.selected, decoded.detailTotal, decoded.detailOffset, decoded.details, decoded.forgetPermission, decoded.adminPermission);
    }

    public static ResearchGuiSnapshot capture(ResearchPlayerView view, ResearchRegistry registry, Request request) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(registry);
        Objects.requireNonNull(request);
        if (!registry.isFrozen()) throw new IllegalStateException("Research GUI requires frozen definitions");
        var source = new ObjectArrayList<Entry>();
        var manualByEntry = new Long2ObjectOpenHashMap<ResearchPlayerView.ManualRow>();
        if (view.team().queuePermission() == Decision.ALLOWED) {
            for (var manual : view.team().manualRows()) {
                if (manualByEntry.putIfAbsent(manual.entryId(), manual) != null)
                    throw new IllegalArgumentException("Duplicate manual display entry");
            }
        }
        switch (request.tab()) {
            case RESEARCH -> {
                for (var definition : registry.sortedById())
                    source.add(row(view, definition, view.team().viewPermission() == Decision.ALLOWED
                        ? view.team().structuralTargets().getInt(definition.id()) : 0, 0, null, null, null));
            }
            case QUEUE -> {
                if (view.team().queuePermission() == Decision.ALLOWED) {
                    for (var queued : view.team().queueRows())
                        source.add(row(view, registry.require(queued.researchId()), queued.targetLevel(),
                            queued.entryId(), manualByEntry.get(queued.entryId()), null, null));
                }
            }
            case REWARDS -> {
                if (view.personalPermission() == Decision.ALLOWED) {
                    var claims = new ObjectArrayList<>(view.claims());
                    claims.sort(Comparator.comparing(ResearchPlayerView.ClaimSummary::researchId, ID_ORDER)
                                          .thenComparingInt(ResearchPlayerView.ClaimSummary::level));
                    for (var claim : claims)
                        source.add(row(view, registry.require(claim.researchId()), claim.level(), 0,
                            null, claim.status(), claim.selectedGroupId().orElse(null)));
                }
            }
        }
        int offset = pageOffset(request.offset(), source.size(), MAX_ROWS);
        var rows = page(source, offset, MAX_ROWS);
        Entry selectedRow = null;
        for (var row : rows) if (row.selection().equals(request.selection())) selectedRow = row;
        boolean matched = selectedRow != null;
        if (selectedRow == null && !rows.isEmpty()) selectedRow = rows.getFirst();
        var selectedManual = selectedRow == null ? null : manualByEntry.get(selectedRow.entryId());
        if (selectedRow != null && request.tab() == Tab.RESEARCH && view.team().queuePermission() == Decision.ALLOWED) {
            for (var queued : view.team().queueRows()) {
                if (queued.researchId().equals(selectedRow.id()) && queued.targetLevel() == selectedRow.targetLevel()) {
                    selectedManual = manualByEntry.get(queued.entryId());
                    break;
                }
            }
        }
        var allDetails = selectedRow == null ? ObjectList.<Detail>of()
            : details(view, registry, request.tab(), selectedRow, selectedManual);
        int detailOffset = pageOffset(matched ? request.detailOffset() : 0, allDetails.size(), MAX_DETAILS);
        return new ResearchGuiSnapshot(true, view.savePending(), view.team().status(), view.personalPermission(), view.team().viewPermission(),
            view.team().queuePermission(), view.team().startPermission(), view.team().blacklistPermission(), request.tab(), source.size(), offset,
            rows, selectedRow == null ? null : selectedRow.selection(), allDetails.size(), detailOffset, page(allDetails, detailOffset, MAX_DETAILS), view.forgetPermission(), view.adminPermission());
    }

    private static Entry row(ResearchPlayerView view, ResearchDefinition definition, int target, long entryId,
                             @Nullable ResearchPlayerView.ManualRow manual, @Nullable RewardClaim.Status claim,
                             @Nullable ResourceLocation selectedGroup) {
        int personal = view.personalPermission() == Decision.ALLOWED ? view.personalLevels().getInt(definition.id()) : 0;
        int shared = view.team().viewPermission() == Decision.ALLOWED ? view.team().sharedLevels().getInt(definition.id()) : 0;
        int displayLevel = target > 0 ? target : Math.max(1, Math.max(personal, shared));
        int tier = definition.researchTierForLevel(displayLevel);
        return new Entry(definition.id(), personal, shared, target, tier,
            view.team().viewPermission() == Decision.ALLOWED && view.team().blacklist().contains(definition.id()), entryId,
            manual == null ? null : manual.status(), manual == null ? null : manual.pauseReason(), manual == null ? null : manual.contributor(),
            claim, selectedGroup);
    }

    private static ObjectList<Detail> details(ResearchPlayerView view, ResearchRegistry registry, Tab tab, Entry selected,
                                              @Nullable ResearchPlayerView.ManualRow manual) {
        var result = new ObjectArrayList<Detail>();
        if (tab == Tab.REWARDS) {
            for (var claim : view.claims()) {
                if (claim.researchId().equals(selected.id()) && claim.level() == selected.targetLevel()) {
                    for (var group : claim.choiceGroupIds()) result.add(new Detail(Kind.REWARD_CHOICE, group, 0, 0, 0));
                    return result;
                }
            }
            throw new IllegalStateException("Selected reward row lost its own claim");
        }
        if (selected.targetLevel() == 0) return result;
        var definition = registry.require(selected.id());
        boolean head = false;
        if (view.team().queuePermission() == Decision.ALLOWED && !view.team().queueRows().isEmpty()) {
            var first = view.team().queueRows().getFirst();
            head = first.researchId().equals(selected.id()) && first.targetLevel() == selected.targetLevel()
                && (tab != Tab.QUEUE || first.entryId() == selected.entryId());
        }
        var demands = definition.totalResourceDemandForLevel(selected.targetLevel());
        var types = new ObjectArrayList<>(demands.keySet());
        types.sort(Comparator.comparing(NetworkResourceType::id, ID_ORDER));
        for (var type : types)
            result.add(new Detail(Kind.NUMERIC, type.id(), 0,
                head ? view.team().numericProgress().getLong(type.id()) : 0, demands.getLong(type)));
        if (selected.tier() == 0)
            result.addAll(materialDetails(definition.materialRequirementsForLevel(selected.targetLevel()), manual));
        return result;
    }

    /**
     * Reads the exact static input amount, without instantiating inventory or invoking execution hooks.
     */
    static ObjectList<Detail> materialDetails(List<RecipeRequirement<?>> requirements, @Nullable ResearchPlayerView.ManualRow manual) {
        var result = new ObjectArrayList<Detail>();
        for (var requirement : requirements) {
            if (requirement.type() != RecipeRequirements.RESOURCES || !(requirement.payload() instanceof ResourceRequirement(
                var inputs, var inputsPerTick, var outputsPerTick, var outputs, var voidExcess, var chance,
                var randomOutputs, var fuzzyInputs, var dynamicInputs, var inputAllocation
            ))
                || !inputsPerTick.isEmpty() || !outputsPerTick.isEmpty() || !outputs.isEmpty()
                || voidExcess || !chance.equals(ResourceChance.ALWAYS) || randomOutputs != null || !fuzzyInputs.isEmpty() || !dynamicInputs.isEmpty()
                || inputAllocation != InputAllocation.SPLITTABLE) {
                throw new IllegalArgumentException("Manual research display requires static resource inputs with default source allocation");
            }
            for (int input = 0; input < inputs.size(); input++) {
                var key = new ManualMaterialKey(requirement.id(), input);
                result.add(new Detail(Kind.MATERIAL, requirement.id(), input, manual == null ? 0 : manual.work().getLong(key),
                    inputs.get(input).amount()));
            }
        }
        return result;
    }

    private static ResearchGuiSnapshot read(ByteBuf data) {
        if (data.readableBytes() < Integer.BYTES)
            throw new IllegalArgumentException("Missing research GUI packet length");
        int length = data.readInt();
        if (length < 1 || length > MAX_PACKET_BYTES || length > data.readableBytes())
            throw new IllegalArgumentException("Invalid research GUI packet length");
        var packet = new PacketBuffer(data.readSlice(length));
        try {
            return readBody(packet);
        } catch (IndexOutOfBoundsException failure) {
            throw new IllegalArgumentException("Truncated research GUI packet body", failure);
        }
    }

    private static ResearchGuiSnapshot readBody(PacketBuffer packet) {
        if (packet.readUnsignedByte() != VERSION)
            throw new IllegalArgumentException("Unsupported research GUI packet version");
        boolean initialized = readBoolean(packet), savePending = readBoolean(packet);
        var teamStatus = readEnum(packet, ResearchPlayerView.TeamStatus.values());
        var personal = readEnum(packet, Decision.values());
        var team = readEnum(packet, Decision.values());
        var queue = readEnum(packet, Decision.values());
        var start = readEnum(packet, Decision.values());
        var blacklist = readEnum(packet, Decision.values());
        var forget = readEnum(packet, Decision.values());
        var admin = readEnum(packet, Decision.values());
        var tab = readEnum(packet, Tab.values());
        int total = packet.readInt(), offset = packet.readInt(), count = packet.readUnsignedByte();
        if (count > MAX_ROWS) throw new IllegalArgumentException("Too many research GUI rows");
        var rows = new ObjectArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            var id = readId(packet);
            int personalLevel = packet.readInt(), sharedLevel = packet.readInt(), target = packet.readInt(), tier = packet.readInt();
            boolean blacklisted = readBoolean(packet);
            long entryId = packet.readLong();
            var manual = readNullableEnum(packet, ManualResearchSession.Status.values());
            var pause = readNullableEnum(packet, ManualResearchSession.PauseReason.values());
            var contributor = readBoolean(packet) ? packet.readUniqueId() : null;
            var claim = readNullableEnum(packet, RewardClaim.Status.values());
            var selectedGroup = readBoolean(packet) ? readId(packet) : null;
            rows.add(new Entry(id, personalLevel, sharedLevel, target, tier, blacklisted, entryId, manual, pause, contributor, claim, selectedGroup));
        }
        var selected = readBoolean(packet) ? new Selection(readId(packet), packet.readInt(), packet.readLong()) : null;
        int detailTotal = packet.readInt(), detailOffset = packet.readInt(), detailCount = packet.readUnsignedByte();
        if (detailCount > MAX_DETAILS) throw new IllegalArgumentException("Too many research GUI details");
        var details = new ObjectArrayList<Detail>(detailCount);
        for (int i = 0; i < detailCount; i++)
            details.add(new Detail(readEnum(packet, Kind.values()), readId(packet), packet.readInt(), packet.readLong(), packet.readLong()));
        if (packet.isReadable()) throw new IllegalArgumentException("Trailing research GUI packet data");
        return new ResearchGuiSnapshot(initialized, savePending, teamStatus, personal, team, queue, start, blacklist, tab,
            total, offset, rows, selected, detailTotal, detailOffset, details, forget, admin);
    }

    private static boolean readBoolean(PacketBuffer packet) {
        int value = packet.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid research GUI boolean");
        return value == 1;
    }

    private static <E extends Enum<E>> E readEnum(PacketBuffer packet, E[] values) {
        int value = packet.readUnsignedByte();
        if (value >= values.length) throw new IllegalArgumentException("Invalid research GUI enum");
        return values[value];
    }

    @Nullable
    private static <E extends Enum<E>> E readNullableEnum(PacketBuffer packet, E[] values) {
        return readBoolean(packet) ? readEnum(packet, values) : null;
    }

    private static void writeEnum(PacketBuffer packet, @Nullable Enum<?> value) {
        packet.writeBoolean(value != null);
        if (value != null) packet.writeByte(value.ordinal());
    }

    private static void writeId(PacketBuffer packet, @Nullable ResourceLocation id) {
        packet.writeBoolean(id != null);
        if (id != null) packet.writeString(id.toString());
    }

    private static ResourceLocation readId(PacketBuffer packet) {
        return requireId(StrictNbt.requireId(packet.readString(MAX_ID_LENGTH)));
    }

    private static ResourceLocation requireId(ResourceLocation id) {
        Objects.requireNonNull(id);
        if (id.toString().length() > MAX_ID_LENGTH)
            throw new IllegalArgumentException("Research GUI ID exceeds packet bound");
        return StrictNbt.requireId(id.toString());
    }

    private static int pageOffset(int requested, int total, int limit) {
        return total == 0 ? 0 : Math.min(requested / limit, (total - 1) / limit) * limit;
    }

    private static void requirePage(int total, int offset, int count, int limit) {
        if (total < 0 || offset < 0 || offset % limit != 0 || offset > Math.max(0, total - 1)
            || count != Math.min(limit, total - offset))
            throw new IllegalArgumentException("Invalid research GUI page bounds");
    }

    private static <T> ObjectList<T> page(ObjectList<T> source, int offset, int limit) {
        return source.subList(offset, offset + Math.min(limit, source.size() - offset));
    }

    private static <T> ObjectList<T> frozen(ObjectList<T> source) {
        var copy = new ObjectArrayList<>(Objects.requireNonNull(source));
        for (var row : copy) Objects.requireNonNull(row);
        return ObjectLists.unmodifiable(copy);
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        var body = Unpooled.buffer(1024, MAX_PACKET_BYTES);
        try {
            var packet = new PacketBuffer(body);
            packet.writeByte(VERSION).writeBoolean(initialized).writeBoolean(savePending).writeByte(teamStatus.ordinal());
            packet.writeByte(personalPermission.ordinal()).writeByte(teamViewPermission.ordinal()).writeByte(queuePermission.ordinal())
                  .writeByte(startPermission.ordinal()).writeByte(blacklistPermission.ordinal())
                  .writeByte(forgetPermission.ordinal()).writeByte(adminPermission.ordinal()).writeByte(tab.ordinal());
            packet.writeInt(total).writeInt(offset).writeByte(rows.size());
            for (var row : rows) {
                packet.writeString(row.id().toString()).writeInt(row.personalLevel()).writeInt(row.sharedLevel()).writeInt(row.targetLevel())
                      .writeInt(row.tier()).writeBoolean(row.blacklisted()).writeLong(row.entryId());
                writeEnum(packet, row.manualStatus());
                writeEnum(packet, row.pauseReason());
                packet.writeBoolean(row.contributor() != null);
                if (row.contributor() != null) packet.writeUniqueId(row.contributor());
                writeEnum(packet, row.claimStatus());
                writeId(packet, row.selectedGroup());
            }
            packet.writeBoolean(selected != null);
            if (selected != null)
                packet.writeString(selected.researchId().toString()).writeInt(selected.level()).writeLong(selected.entryId());
            packet.writeInt(detailTotal).writeInt(detailOffset).writeByte(details.size());
            for (var detail : details) {
                packet.writeByte(detail.kind().ordinal());
                packet.writeString(detail.id().toString()).writeInt(detail.inputIndex()).writeLong(detail.current()).writeLong(detail.required());
            }
            data.writeInt(body.readableBytes()).writeBytes(body, body.readerIndex(), body.readableBytes());
        } finally {
            body.release();
        }
    }

    public enum Tab {RESEARCH, QUEUE, REWARDS}

    public enum Kind {NUMERIC, MATERIAL, REWARD_CHOICE}

    public record Selection(ResourceLocation researchId, int level, long entryId) {
        public Selection {
            requireId(researchId);
            if (level < 0 || entryId < 0) throw new IllegalArgumentException("Negative research selection");
        }
    }

    public record Request(Tab tab, int offset, @Nullable Selection selection, int detailOffset) {
        public Request {
            Objects.requireNonNull(tab);
            if (offset < 0 || detailOffset < 0) throw new IllegalArgumentException("Negative research page request");
            if (selection != null && (tab == Tab.QUEUE ? selection.entryId() < 1 || selection.level() < 1
                : selection.entryId() != 0 || tab == Tab.REWARDS && selection.level() < 1)) {
                throw new IllegalArgumentException("Research selection does not match the requested tab");
            }
        }
    }

    public record Entry(ResourceLocation id, int personalLevel, int sharedLevel, int targetLevel, int tier,
                        boolean blacklisted, long entryId, @Nullable ManualResearchSession.Status manualStatus,
                        @Nullable ManualResearchSession.PauseReason pauseReason, @Nullable UUID contributor,
                        @Nullable RewardClaim.Status claimStatus, @Nullable ResourceLocation selectedGroup) {
        public Entry {
            requireId(id);
            if (personalLevel < 0 || sharedLevel < 0 || targetLevel < 0 || tier < 0 || entryId < 0) {
                throw new IllegalArgumentException("Negative research row value");
            }
            if (selectedGroup != null) requireId(selectedGroup);
            if (manualStatus == null && (pauseReason != null || contributor != null)
                || manualStatus != null && (entryId == 0 || tier != 0 || claimStatus != null))
                throw new IllegalArgumentException("Invalid manual row state");
            if (manualStatus != null) {
                boolean valid = switch (manualStatus) {
                    case ACTIVE -> contributor != null && pauseReason == null;
                    case PAUSED, MATERIAL_ABORT_RECEIPT_PENDING -> contributor == null && pauseReason != null;
                    default -> contributor == null && pauseReason == null;
                };
                if (!valid) throw new IllegalArgumentException("Manual research row lost its state metadata");
            }
            if (claimStatus == null ? selectedGroup != null
                : (claimStatus == RewardClaim.Status.UNCLAIMED) == (selectedGroup != null))
                throw new IllegalArgumentException("Invalid reward choice state");
        }

        public Selection selection() {
            return new Selection(id, targetLevel, entryId);
        }
    }

    /**
     * MATERIAL identifies a static input position; REWARD_CHOICE identifies a selectable group.
     */
    public record Detail(Kind kind, ResourceLocation id, int inputIndex, long current, long required) {
        public Detail {
            Objects.requireNonNull(kind);
            requireId(id);
            if (inputIndex < 0 || current < 0 || required < 0 || current > required)
                throw new IllegalArgumentException("Invalid research detail quantity");
            if (kind != Kind.MATERIAL && inputIndex != 0 || kind == Kind.REWARD_CHOICE && (current != 0 || required != 0)) {
                throw new IllegalArgumentException("Invalid research detail identity");
            }
        }
    }
}
