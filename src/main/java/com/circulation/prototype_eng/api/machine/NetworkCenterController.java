package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.network.NetworkCenter;

/**
 * Ready-to-use network-center runtime. It has no recipe hosts or business ticks; the physical host's
 * existing structure service maintains formation and MachineNetworkDevice publishes network membership.
 */
public class NetworkCenterController extends MachineInstance implements NetworkCenter {
    private final int networkLevel;
    private final long energyPerTick;

    public NetworkCenterController(MachineType type, int networkLevel) {
        this(type, networkLevel, 0);
    }

    public NetworkCenterController(MachineType type, int networkLevel, long energyPerTick) {
        super(type);
        if (networkLevel < 1 || energyPerTick < 0)
            throw new IllegalArgumentException("Invalid network center level or energy cost");
        this.networkLevel = networkLevel;
        this.energyPerTick = energyPerTick;
    }

    @Override
    public int networkLevel() {
        return networkLevel;
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
