package com.circulation.prototype_eng.container;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
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

    @SuppressWarnings("DataFlowIssue")
    public void receive(ResearchOverviewPacket packet) {
        if (packet.reset()) byId.clear();
        if (packet.definitions() != null) {
            for (int index = 0; index < packet.definitions().size(); index++) {
                var definition = packet.definitions().get(index);
                byId.put(definition.id(), new ResearchOverviewPacket.Entry(definition, ResearchOverviewPacket.State.EMPTY));
            }
        }
        for (int index = 0; index < packet.states().size(); index++) {
            var state = packet.states().get(index);
            byId.compute(state.id(), (id, previous) -> new ResearchOverviewPacket.Entry(previous.definition(), state));
        }
        if (packet.queue() != null) queue = packet.queue();
        if (packet.databases() != null) databases = packet.databases();
        if (packet.currentChanged()) current = packet.applyCurrent(current);
        if (packet.currentChanged() && current != null) mergeCurrent(current);
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

    public List<ResearchOverviewPacket.Queue> queue() {
        return queue;
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
