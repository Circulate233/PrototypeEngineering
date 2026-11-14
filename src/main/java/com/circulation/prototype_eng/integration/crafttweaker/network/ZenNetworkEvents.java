package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.NetworkDeviceEvents;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.Events")
@SuppressWarnings("unused")
public final class ZenNetworkEvents {
    private ZenNetworkEvents() {
    }

    @ZenMethod
    public static void onDevice(ZenNetworkDeviceCallback callback) {
        NetworkDeviceEvents.register(event -> callback.apply(new ZenNetworkDeviceEvent(event)));
    }
}
