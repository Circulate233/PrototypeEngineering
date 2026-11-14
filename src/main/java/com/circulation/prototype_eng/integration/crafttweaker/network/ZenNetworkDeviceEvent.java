package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.NetworkDeviceEvent;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

@ZenRegister
@ZenClass("mods.prototype_eng.network.DeviceEvent")
@SuppressWarnings("unused")
public final class ZenNetworkDeviceEvent {
    private final NetworkDeviceEvent value;
    private final ZenNetworkData network;
    private final ZenNetworkValues values;

    public ZenNetworkDeviceEvent(NetworkDeviceEvent value) {
        this.value = value;
        network = new ZenNetworkData(value.network());
        values = new ZenNetworkValues(value.values());
    }

    public NetworkDeviceEvent value() {
        return value;
    }
    @ZenGetter("network")
    public ZenNetworkData network() {
        return network;
    }

    @ZenGetter("values")
    public ZenNetworkValues values() {
        return values;
    }

    @ZenGetter("machineType")
    @SuppressWarnings("DataFlowIssue")
    public String machineType() {
        return value.machine().type().id().toString();
    }
}
