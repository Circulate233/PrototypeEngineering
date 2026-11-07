package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.ResearchSnapshots;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Frozen import intent and receipt. Completed levels, disk tier and database tier remain independent.
 * Runtime revisions are excluded so a saved receipt identifies the same operation after restart.
 */
public record ResearchDiskCommit(UUID operationId, UUID actor, String teamId, int diskTier,
                                 Object2IntMap<ResourceLocation> entries,
                                 DataCenterResearchSnapshot before, DataCenterResearchSnapshot after) {
    public ResearchDiskCommit {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(actor);
        if (Objects.requireNonNull(teamId).isBlank() || !teamId.equals(teamId.trim()) || diskTier < 0) {
            throw new IllegalArgumentException("Invalid disk import identity or tier");
        }
        entries = ResearchSnapshots.levels(Objects.requireNonNull(entries));
        if (entries.isEmpty() || entries.size() > ResearchDiskData.MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid disk import entry count");
        }
        before = withoutRevision(Objects.requireNonNull(before));
        after = withoutRevision(Objects.requireNonNull(after));
        if (!before.dataCenterId().equals(after.dataCenterId()) || before.level() != after.level()
            || before.capacity() != after.capacity() || diskTier > before.level()) {
            throw new IllegalArgumentException("Disk import changes database identity or metadata");
        }
        var expected = new Object2IntOpenHashMap<>(before.storedResearch());
        for (var entry : entries.object2IntEntrySet()) {
            Objects.requireNonNull(entry.getKey());
            if (entry.getIntValue() < 1) throw new IllegalArgumentException("Disk import levels must be positive");
            if (entry.getIntValue() > expected.getInt(entry.getKey())) expected.put(entry.getKey(), entry.getIntValue());
        }
        if (!expected.equals(after.storedResearch())) {
            throw new IllegalArgumentException("Disk import after contents differ from its complete batch");
        }
    }

    public boolean matchesBefore(DataCenterResearchSnapshot snapshot) {
        return before.equals(withoutRevision(Objects.requireNonNull(snapshot)));
    }

    private static DataCenterResearchSnapshot withoutRevision(DataCenterResearchSnapshot snapshot) {
        return new DataCenterResearchSnapshot(snapshot.dataCenterId(), snapshot.level(), snapshot.capacity(), 0, snapshot.storedResearch());
    }
}
