package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.Objects;
import java.util.UUID;

/**
 * Resource projection of an active participant's validated snapshot. The participant runtime still
 * owns work units, sources and version validation at commit; this projection never grants those capabilities.
 */
public record NetworkContribution(UUID participantId, UUID ownerId, long epoch, long version,
                                  Object2LongMap<NetworkResourceType> resourcesPerTick) {
    public NetworkContribution {
        Objects.requireNonNull(participantId);
        Objects.requireNonNull(ownerId);
        if (epoch < 0 || version < 0) {
            throw new IllegalArgumentException("Invalid participant snapshot generation");
        }
        resourcesPerTick = NetworkResourceModifier.copyResources(resourcesPerTick);
    }
}
