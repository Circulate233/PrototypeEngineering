package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.network.NetworkCenter;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;

/**
 * Ready-to-use network-center runtime. It has no recipe hosts or business ticks; the physical host's
 * existing structure service maintains formation and MachineNetworkDevice publishes network membership.
 */
public class NetworkCenterController extends MachineInstance implements NetworkCenter {
    private final int networkLevel;
    private final long energyPerTick;
    private final Reference2DoubleMap<NetworkResourceType> networkResources;
    private final long threadCapacity;

    public NetworkCenterController(MachineType type, int networkLevel) {
        this(type, networkLevel, 0);
    }

    public NetworkCenterController(MachineType type, int networkLevel, long energyPerTick) {
        this(type, networkLevel, 0, energyPerTick);
    }

    public NetworkCenterController(MachineType type, int networkLevel, long threadCapacity, long energyPerTick) {
        super(type);
        if (networkLevel < 1 || threadCapacity < 0 || energyPerTick < 0)
            throw new IllegalArgumentException("Invalid network center level, thread capacity or energy cost");
        this.networkLevel = networkLevel;
        this.energyPerTick = energyPerTick;
        this.threadCapacity = threadCapacity;
        networkResources = Reference2DoubleMaps.emptyMap();
    }

    @Override
    public int networkLevel() {
        return networkLevel;
    }

    @Override
    public long providedConcurrency() {
        return threadCapacity;
    }

    @Override
    public Reference2DoubleMap<NetworkResourceType> networkResources() {
        return networkResources;
    }

    @Override
    public boolean requiresTick() {
        return false;
    }

    @Override
    public long energyPerTick() {
        return energyPerTick;
    }

    /**
     * The center establishes the network; it cannot require its own pre-existing permit.
     */
    @Override
    public boolean requiresNetwork() {
        return false;
    }
}
