package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import org.jetbrains.annotations.Nullable;


/**
 * Owner-thread capture of a loaded device. No World, tile or mutable runtime crosses into planning.
 * The host must retain its exact registration separately and recheck it before publishing a plan.
 * Resource quantities are borrowed zero-default immutable data for this snapshot's lifetime.
 */
public record NetworkDeviceSnapshot(PersistentMachineIdentity identity, NetworkDeviceRole role, int level,
                                    boolean formed, long loadOrder,
                                    long concurrencyPerTick,
                                    Reference2DoubleMap<NetworkResourceType> resourcesPerTick,
                                    NetworkResourceModifier resourceModifier, int dataCenterCapacity,
                                    int storedResearchCount,
                                    @Nullable DataCenterResearchSnapshot dataCenterResearch) {
    /**
     * Count-only captures support topology planning; runtime data centers require versioned real contents.
     */
    public NetworkDeviceSnapshot(PersistentMachineIdentity identity, NetworkDeviceRole role, int level,
                                 boolean formed, long loadOrder, Reference2DoubleMap<NetworkResourceType> resourcesPerTick,
                                 NetworkResourceModifier resourceModifier, int dataCenterCapacity, int storedResearchCount) {
        this(identity, role, level, formed, loadOrder, 0, resourcesPerTick, resourceModifier, dataCenterCapacity, storedResearchCount, null);
    }

    public NetworkDeviceSnapshot(PersistentMachineIdentity identity, NetworkDeviceRole role, int level,
                                 boolean formed, long loadOrder, Reference2DoubleMap<NetworkResourceType> resourcesPerTick,
                                 NetworkResourceModifier resourceModifier, int dataCenterCapacity, int storedResearchCount,
                                 @Nullable DataCenterResearchSnapshot dataCenterResearch) {
        this(identity, role, level, formed, loadOrder, 0, resourcesPerTick, resourceModifier, dataCenterCapacity, storedResearchCount, dataCenterResearch);
    }

    public NetworkDeviceSnapshot(PersistentMachineIdentity identity, NetworkDeviceRole role, int level,
                                 boolean formed, long loadOrder, long concurrencyPerTick, Reference2DoubleMap<NetworkResourceType> resourcesPerTick,
                                 NetworkResourceModifier resourceModifier, int dataCenterCapacity, int storedResearchCount) {
        this(identity, role, level, formed, loadOrder, concurrencyPerTick, resourcesPerTick, resourceModifier, dataCenterCapacity, storedResearchCount, null);
    }


}
