package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.NetworkData;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.Device")
@SuppressWarnings("unused")
public record ZenNetworkDevice(NetworkData.Device value) {
    @ZenGetter("type")
    public String type() {
        return value.location().machineType().toString();
    }

    @ZenGetter("id")
    public String id() {
        return value.snapshot().identity().machineId().toString();
    }

    @ZenGetter("owner")
    public String owner() {
        return value.snapshot().identity().ownerId().toString();
    }

    @ZenGetter("role")
    public String role() {
        return value.snapshot().role().name();
    }

    @ZenGetter("level")
    public int level() {
        return value.snapshot().level();
    }

    @ZenGetter("formed")
    public boolean formed() {
        return value.snapshot().formed();
    }

    @ZenGetter("dimension")
    public int dimension() {
        return value.location().dimension();
    }
}
