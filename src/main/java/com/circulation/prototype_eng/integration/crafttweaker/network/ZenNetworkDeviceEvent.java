package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.NetworkDeviceEvent;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

import java.util.Objects;

@ZenRegister
@ZenClass("mods.prototype_eng.network.DeviceEvent")
@SuppressWarnings("unused")
public record ZenNetworkDeviceEvent(NetworkDeviceEvent value) {
    @ZenGetter("network")
    public ZenNetworkData network() {
        return new ZenNetworkData(value.network());
    }

    @ZenGetter("values")
    public ZenNetworkValues values() {
        return new ZenNetworkValues(value.values());
    }

    @ZenGetter("machineType")
    public String machineType() {
        return Objects.requireNonNull(value.machine().type()).id().toString();
    }
}
