package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.ComputerRecipeEvent;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.ComputerRecipeEvent")
@SuppressWarnings("unused")
public record ZenComputerRecipeEvent(ComputerRecipeEvent value) {
    @ZenGetter("network")
    public ZenNetworkData network() {
        return new ZenNetworkData(value.network());
    }

    @ZenGetter("values")
    public ZenNetworkValues values() {
        return new ZenNetworkValues(value.values());
    }

    @ZenGetter("stage")
    public String stage() {
        return value.stage().name();
    }

    @ZenGetter("progress")
    public double progress() {
        return value.process().state().progress();
    }

    @ZenGetter("duration")
    public int duration() {
        return value.process().durationTicks();
    }
}
