package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Per-container server delta baseline. Definitions are sent when the queue head changes.
 */
public final class ResearchStationDisplay {
    private final ResearchRegistry registry;
    private @Nullable TeamResearchQueue.Key previousHead;
    private String previousTeam = "";
    private @Nullable ResearchStationPacket.Definition definition;
    private List<TeamResearchQueue.Entry> previousQueue = List.of();
    private List<ResearchStationPacket.QueueEntry> queue = List.of();
    private long[] available = new long[0], remaining = new long[0];
    private double[] occupied = new double[0], capacity = new double[0], accumulated = new double[0];
    private boolean initialized;

    public ResearchStationDisplay(ResearchRegistry registry) {
        this.registry = registry;
    }

    /**
     * A nonempty queue must include progress for its head.
     */
    @SuppressWarnings("DataFlowIssue")
    public @Nullable ResearchStationPacket capture(ResearchStationView view) {
        long entryId = view.queue().isEmpty() ? 0 : view.queue().getFirst().key().queueEntryId();
        var headKey = view.queue().isEmpty() ? null : view.queue().getFirst().key();
        boolean reset = !initialized || !view.teamId().equals(previousTeam) || !Objects.equals(headKey, previousHead);
        previousHead = headKey;
        previousTeam = view.teamId();
        if (reset) {
            definition = null;
            if (entryId != 0) {
                var head = view.queue().getFirst().key();
                var research = registry.require(head.researchId());
                var keys = new ObjectArrayList<>(research.stageForLevel(head.targetLevel()).materialTotals().keySet());
                var resources = new ObjectArrayList<ResearchStationPacket.Resource>();
                for (var entry : view.resourceTotals().reference2DoubleEntrySet())
                    if (entry.getDoubleValue() > 0)
                        resources.add(new ResearchStationPacket.Resource(entry.getKey(), entry.getDoubleValue()));
                definition = new ResearchStationPacket.Definition(entryId, research.id(), head.targetLevel(),
                    view.durationTicks(), keys, resources);
            }
            int materialCount = definition == null ? 0 : definition.materials().size();
            int resourceCount = definition == null ? 0 : definition.resources().size();
            available = new long[materialCount];
            remaining = new long[materialCount];
            occupied = new double[resourceCount];
            capacity = new double[resourceCount];
            accumulated = new double[resourceCount];
        }
        boolean queueChanged = !initialized || !previousQueue.equals(view.queue());
        if (queueChanged) {
            previousQueue = view.queue();
            var rows = new ObjectArrayList<ResearchStationPacket.QueueEntry>();
            var elements = view.queue();
            for (int index1 = 0; index1 < elements.size(); index1++) {
                var entry = elements.get(index1);
                rows.add(new ResearchStationPacket.QueueEntry(entry.key().queueEntryId(),
                    entry.key().researchId(), entry.key().targetLevel()));
            }
            queue = rows;
        }
        ObjectArrayList<ResearchStationPacket.MaterialChange> materials = null;
        ObjectArrayList<ResearchStationPacket.ResourceChange> resources = null;
        if (definition != null) {
            for (int index = 0; index < available.length; index++) {
                var key = definition.materials().get(index);
                long nextRemaining = view.progress().remainingMaterials().getLong(key);
                long nextAvailable = Math.min(nextRemaining, view.availableMaterials().getLong(key));
                int fields = (reset || available[index] != nextAvailable ? ResearchStationPacket.FIRST : 0)
                    | (reset || remaining[index] != nextRemaining ? ResearchStationPacket.SECOND : 0);
                if (fields != 0) {
                    if (materials == null) materials = new ObjectArrayList<>();
                    materials.add(new ResearchStationPacket.MaterialChange(index, fields, nextAvailable, nextRemaining));
                }
                available[index] = nextAvailable;
                remaining[index] = nextRemaining;
            }
            for (int index = 0; index < occupied.length; index++) {
                var type = definition.resources().get(index).type();
                double nextOccupied = view.occupiedResources().getDouble(type), nextCapacity = view.capacity().getDouble(type), nextAccumulated = view.progress().resources().getDouble(type);
                int fields = (reset || occupied[index] != nextOccupied ? ResearchStationPacket.FIRST : 0)
                    | (reset || capacity[index] != nextCapacity ? ResearchStationPacket.SECOND : 0)
                    | (reset || accumulated[index] != nextAccumulated ? ResearchStationPacket.THIRD : 0);
                if (fields != 0) {
                    if (resources == null) resources = new ObjectArrayList<>();
                    resources.add(new ResearchStationPacket.ResourceChange(index, fields, nextOccupied, nextCapacity, nextAccumulated));
                }
                occupied[index] = nextOccupied;
                capacity[index] = nextCapacity;
                accumulated[index] = nextAccumulated;
            }
        }
        initialized = true;
        return reset || queueChanged || materials != null || resources != null
            ? new ResearchStationPacket(reset, definition, queueChanged ? queue : null, materials == null ? List.of() : materials, resources == null ? List.of() : resources) : null;
    }

    public ResearchStationPacket fullPacket() {
        var materials = new ObjectArrayList<ResearchStationPacket.MaterialChange>();
        for (int index = 0; index < available.length; index++)
            materials.add(new ResearchStationPacket.MaterialChange(index, 3, available[index], remaining[index]));
        var resources = new ObjectArrayList<ResearchStationPacket.ResourceChange>();
        for (int index = 0; index < occupied.length; index++)
            resources.add(new ResearchStationPacket.ResourceChange(index, 7, occupied[index], capacity[index], accumulated[index]));
        return new ResearchStationPacket(true, definition, queue, materials, resources);
    }
}
