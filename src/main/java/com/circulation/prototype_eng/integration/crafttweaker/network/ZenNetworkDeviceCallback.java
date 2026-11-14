package com.circulation.prototype_eng.integration.crafttweaker.network;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.network.DeviceCallback")
@FunctionalInterface
public interface ZenNetworkDeviceCallback {
    void apply(ZenNetworkDeviceEvent event);
}
