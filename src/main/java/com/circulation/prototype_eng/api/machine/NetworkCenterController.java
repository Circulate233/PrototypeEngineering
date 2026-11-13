package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.network.NetworkCenter;

/**
 * Ready-to-use network-center runtime. It has no recipe hosts or business ticks; the physical host's
 * existing structure service maintains formation and MachineNetworkDevice publishes network membership.
 */
public class NetworkCenterController extends MachineInstance implements NetworkCenter {
    private final int networkLevel;

    public NetworkCenterController(MachineType type, int networkLevel) {
        super(type);
        if (networkLevel < 1) throw new IllegalArgumentException("Network center level must be positive");
        this.networkLevel = networkLevel;
    }

    @Override
    public int networkLevel() {
        return networkLevel;
    }

    @Override
    public boolean requiresTick() {
        return false;
    }
}
