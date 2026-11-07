package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.Objects;

/**
 * Owner-thread capture of a loaded device. No World, tile or mutable runtime crosses into planning.
 * The host must retain its exact registration separately and recheck it before publishing a plan.
 */
public record NetworkDeviceSnapshot(PersistentMachineIdentity identity, NetworkDeviceRole role, int level,
        boolean formed, long loadOrder, Object2LongMap<NetworkResourceType> resourcesPerTick,
        NetworkResourceModifier resourceModifier, int dataCenterCapacity, int storedResearchCount) {
    public NetworkDeviceSnapshot {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(role);
        Objects.requireNonNull(resourceModifier);
        if (level < 0 || loadOrder < 0 || storedResearchCount < 0
                || storedResearchCount > dataCenterCapacity || (role == NetworkDeviceRole.DATA_CENTER) != (dataCenterCapacity > 0)) {
            throw new IllegalArgumentException("Invalid network device snapshot");
        }
        if ((role == NetworkDeviceRole.NETWORK_CENTER || role == NetworkDeviceRole.RESEARCH_STATION) && level == 0) {
            throw new IllegalArgumentException("Network centers and research stations require a positive level");
        }
        resourcesPerTick = NetworkResourceModifier.copyResources(resourcesPerTick);
    }
}
