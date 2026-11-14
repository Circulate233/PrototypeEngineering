package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.util.ResourceLocation;

import java.util.UUID;

/**
 * Frozen import intent and receipt. Completed levels, disk tier and database tier remain independent.
 * Runtime revisions are excluded so a saved receipt identifies the same operation after restart.
 */
public record ResearchDiskCommit(UUID operationId, UUID actor, String teamId, int diskTier,
                                 Object2IntMap<ResourceLocation> entries,
                                 DataCenterResearchSnapshot before, DataCenterResearchSnapshot after) {
    public boolean matchesBefore(DataCenterResearchSnapshot snapshot) {
        return before.dataCenterId().equals(snapshot.dataCenterId()) && before.level() == snapshot.level()
            && before.capacity() == snapshot.capacity() && before.storedResearch().equals(snapshot.storedResearch());
    }
}
