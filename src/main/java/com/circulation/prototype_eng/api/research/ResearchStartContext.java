package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable eligibility input, not a reservation or permission to mutate resources.
 * Maps are borrowed read-only for the callback. Do not retain the context or modify its maps. Resource keys use singleton identity.
 */
@SuppressWarnings("unused")
public record ResearchStartContext(ResourceLocation researchId, int targetLevel, @Nullable UUID playerId,
                                   @Nullable String teamId, Object2IntMap<ResourceLocation> teamSharedResearchLevels,
                                   Reference2DoubleMap<NetworkResourceType> availableResources,
                                   int networkCenterLevel) {
    public boolean teamHasNetworkCenter() {
        return networkCenterLevel > 0;
    }
}
