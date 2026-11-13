package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;

import java.util.Objects;
import java.util.UUID;

/**
 * Resource projection of an active participant's validated snapshot. The participant runtime still
 * owns work units, sources and version validation at commit; this projection never grants those capabilities.
 * Resource quantities are borrowed zero-default immutable data for this projection's lifetime.
 */
public record NetworkContribution(UUID participantId, UUID ownerId, long epoch, long version,
                                  long concurrencyPerTick,
                                  Reference2DoubleMap<NetworkResourceType> resourcesPerTick) {
    public NetworkContribution {
        Objects.requireNonNull(participantId);
        Objects.requireNonNull(ownerId);
        if (epoch < 0 || version < 0 || concurrencyPerTick < 0) {
            throw new IllegalArgumentException("Invalid participant snapshot generation");
        }
        NetworkResourceModifier.validateResources(resourcesPerTick);
    }

    public NetworkContribution(UUID participantId, UUID ownerId, long epoch, long version, Reference2DoubleMap<NetworkResourceType> resourcesPerTick) {
        this(participantId, ownerId, epoch, version, 0, resourcesPerTick);
    }
}
