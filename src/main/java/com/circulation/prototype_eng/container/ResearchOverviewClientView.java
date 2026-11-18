package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.research.content.ResearchOverviewLayout;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server projections are borrowed read-only until the next received update.
 */
@SuppressWarnings("unused")
public final class ResearchOverviewClientView {
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ResearchOverviewPacket.Entry> byId = new Object2ObjectLinkedOpenHashMap<>();
    private List<ResearchOverviewPacket.Entry> entries = List.of();
    private List<ResearchOverviewPacket.Queue> queue = List.of();
    private List<ResearchOverviewPacket.Database> databases = List.of();
    private @Nullable ResearchOverviewPacket.Current current;
    private ResearchOverviewPacket.Environment environment = ResearchOverviewPacket.Environment.EMPTY;
    private ResearchOverviewLayout layout = ResearchOverviewLayout.EMPTY;
    private long layoutRevision = -1;
    private @Nullable ResourceLocation requested;
    private @Nullable ResearchOverviewDetail detail;
    private @Nullable ResearchOverviewPacket.Definition summary;
    private long contextSequence = -1;
    private long detailRequest;
    private long detailRevision = -1;
    private boolean detailUnavailable;
    private @Nullable EditResult editResult;

    @SuppressWarnings("DataFlowIssue")
    public void receive(long sequence, ResearchOverviewPacket packet) {
        if (sequence < contextSequence) return;
        boolean contextChanged = sequence != contextSequence;
        contextSequence = sequence;
        if (packet.reset()) {
            byId.clear();
            detail = null;
            summary = null;
        } else if (contextChanged) dropDetail();
        if (packet.reset() || contextChanged) {
            detailRevision = -1;
            detailUnavailable = false;
        }
        if (packet.definitions() != null) {
            for (int index = 0; index < packet.definitions().size(); index++) {
                var definition = packet.definitions().get(index);
                byId.put(definition.id(), new ResearchOverviewPacket.Entry(definition, ResearchOverviewPacket.State.EMPTY));
            }
        }
        boolean currentStateChanged = false;
        for (int index = 0; index < packet.states().size(); index++) {
            var state = packet.states().get(index);
            byId.compute(state.id(), (id, previous) -> new ResearchOverviewPacket.Entry(previous.definition(), state));
            if (current != null && current.id().equals(state.id())) currentStateChanged = true;
        }
        if (packet.queue() != null) queue = packet.queue();
        if (packet.databases() != null) databases = packet.databases();
        if (packet.currentChanged()) current = packet.applyCurrent(current);
        if (current != null && (packet.currentChanged() || currentStateChanged)) mergeCurrent(current);
        if (packet.reset() || packet.definitions() != null || !packet.states().isEmpty() || packet.currentChanged())
            entries = new ObjectArrayList<>(byId.values());
        if (packet.environment() != null) environment = packet.environment();
    }

    private void mergeCurrent(ResearchOverviewPacket.Current value) {
        var entry = byId.get(value.id());
        if (entry == null) return;
        var state = entry.state();
        if (state.progress() == null || state.progress().targetLevel() != value.targetLevel()) return;
        var progress = new ResearchOverviewPacket.Progress(value.targetLevel(), value.elapsed(), value.materials(), value.resources());
        byId.put(value.id(), new ResearchOverviewPacket.Entry(entry.definition(), new ResearchOverviewPacket.State(state.id(), state.personalLevel(), state.teamLevel(), state.startableTarget(),
            state.downloadedLevel(), state.downloadedTo(), state.canStart(), state.canDownload(), state.canForget(), state.selectable(), state.processableLevel(), progress,
            state.claims(), state.otherMemberLevel(), state.availability())));
    }

    public List<ResearchOverviewPacket.Entry> entries() {
        return entries;
    }

    public @Nullable ResearchOverviewPacket.Entry entry(ResourceLocation id) {
        return byId.get(id);
    }

    public ResearchOverviewLayout layout() {
        return layout;
    }

    public long layoutRevision() {
        return layoutRevision;
    }

    void receiveLayout(long revision, ResearchOverviewLayout next) {
        if (revision < layoutRevision) return;
        layoutRevision = revision;
        layout = next;
    }

    void expectDetail(@Nullable ResourceLocation id, long token) {
        requested = id;
        detailRequest = token;
        dropDetail();
        detailRevision = -1;
        detailUnavailable = false;
    }

    void receiveDetail(long sequence, ResourceLocation id, long token, long revision, @Nullable ResearchOverviewDetail next) {
        if (sequence != contextSequence || token != detailRequest || !id.equals(requested) || revision < detailRevision) return;
        dropDetail();
        detailRevision = revision;
        var cached = byId.get(id);
        detailUnavailable = next == null || cached == null;
        if (next != null && cached != null) {
            summary = cached.definition();
            detail = next;
            byId.put(id, new ResearchOverviewPacket.Entry(next.definition(), cached.state()));
            entries = new ObjectArrayList<>(byId.values());
        }
    }

    private void dropDetail() {
        if (detail != null) {
            var previous = byId.get(detail.definition().id());
            if (previous != null && summary != null) {
                byId.put(previous.id(), new ResearchOverviewPacket.Entry(summary, previous.state()));
                entries = new ObjectArrayList<>(byId.values());
            }
            detail = null;
            summary = null;
        }
    }

    public @Nullable ResearchOverviewDetail detail(ResourceLocation id) {
        return detail != null && Objects.equals(requested, id) ? detail : null;
    }

    public long detailRevision(ResourceLocation id) {
        return id.equals(requested) ? detailRevision : -1;
    }

    public boolean detailUnavailable(ResourceLocation id) {
        return detailUnavailable && id.equals(requested);
    }

    void receiveEditResult(EditResult result) {
        editResult = result;
    }

    public @Nullable EditResult editResult() {
        return editResult;
    }

    public record EditResult(long token, long revision, EditStatus status) {
    }

    public enum EditStatus { SAVED, STALE, ERROR }

    public List<ResearchOverviewPacket.Queue> queue() {
        return queue;
    }

    public @Nullable ResearchOverviewPacket.Queue queueSlot(int position) {
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).position() == position) return queue.get(i);
        return null;
    }

    public @Nullable ResearchOverviewPacket.Queue queued(ResourceLocation id) {
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).id().equals(id)) return queue.get(i);
        return null;
    }

    public int queuedCount() {
        return environment.queuedCount();
    }

    public List<ResearchOverviewPacket.Database> databases() {
        return databases;
    }

    public @Nullable ResearchOverviewPacket.Current current() {
        return current;
    }

    public String teamName() {
        return environment.teamName();
    }

    public String teamId() {
        return environment.teamId();
    }

    public int centerLevel() {
        return environment.centerLevel();
    }

    public int stationLevel() {
        return environment.stationLevel();
    }

    public @Nullable UUID activeDatabase() {
        return environment.activeDatabase();
    }

    public boolean canManage() {
        return environment.canManage();
    }

    public boolean savePending() {
        return environment.savePending();
    }

    public boolean editable() {
        return environment.editable();
    }

    public boolean visible() {
        return environment.visible();
    }

    public int databaseLevel() {
        return environment.databaseLevel();
    }

    public String databaseName() {
        return environment.databaseName();
    }

    public int databaseStored() {
        return environment.databaseStored();
    }

    public int databaseCapacity() {
        return environment.databaseCapacity();
    }

    public @Nullable UUID playerId() {
        return environment.playerId();
    }
}
