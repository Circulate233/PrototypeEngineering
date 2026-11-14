package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.util.ResourceLocation;

import java.util.UUID;

/**
 * Detached contents of one physical data center; revision is runtime state, never a saved permit.
 * Stored levels are completed zero-default data borrowed read-only for this snapshot's lifetime.
 */
public record DataCenterResearchSnapshot(UUID dataCenterId, int level, int capacity, long revision,
                                         Object2IntMap<ResourceLocation> storedResearch) {
}
