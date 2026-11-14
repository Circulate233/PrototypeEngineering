package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.ComputerRecipeEvent;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.ComputerRecipeEvent")
@SuppressWarnings("unused")
public final class ZenComputerRecipeEvent {
    private final ComputerRecipeEvent value;
    private final ZenNetworkData network;
    private final ZenNetworkValues values;

    public ZenComputerRecipeEvent(ComputerRecipeEvent value) {
        this.value = value;
        network = new ZenNetworkData(value.network());
        values = new ZenNetworkValues(value.values());
    }

    public ComputerRecipeEvent value() {
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
