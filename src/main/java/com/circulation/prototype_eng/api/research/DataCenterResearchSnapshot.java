package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Detached contents of one physical data center; revision is runtime state, never a saved permit. */
public record DataCenterResearchSnapshot(UUID dataCenterId, int level, int capacity, long revision,
                                        Object2IntMap<ResourceLocation> storedResearch) {
    public DataCenterResearchSnapshot {
        Objects.requireNonNull(dataCenterId);
        if (level < 0 || capacity < 1 || revision < 0 || storedResearch.size() > capacity) {
            throw new IllegalArgumentException("Invalid data-center snapshot metadata");
        }
        for (var entry : storedResearch.object2IntEntrySet()) {
            Objects.requireNonNull(entry.getKey());
            if (entry.getIntValue() < 1) { throw new IllegalArgumentException("Stored research levels must be positive"); }
        }
        storedResearch = ResearchSnapshots.levels(storedResearch);
    }
}
