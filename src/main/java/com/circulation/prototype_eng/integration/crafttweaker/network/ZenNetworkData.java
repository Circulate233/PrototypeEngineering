package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.NetworkData;
import net.minecraft.util.ResourceLocation;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.Data")
@SuppressWarnings("unused")
public record ZenNetworkData(NetworkData value) {
    @ZenGetter("teamId")
    public String teamId() {
        return value.teamId();
    }

    @ZenGetter("tick")
    public long tick() {
        return value.tick();
    }

    @ZenMethod
    public boolean hasDevice(String type) {
        return value.hasDevice(new ResourceLocation(type));
    }

    @ZenGetter("devices")
    public ZenNetworkDevice[] devices() {
        var rows = value.devices();
        var result = new ZenNetworkDevice[rows.size()];
        for (int i = 0; i < result.length; i++) result[i] = new ZenNetworkDevice(rows.get(i));
        return result;
    }
}
