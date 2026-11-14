package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.NetworkResourceState;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.Values")
@SuppressWarnings("unused")
public record ZenNetworkValues(NetworkResourceState value) {
    @ZenGetter("resources")
    public ZenResourceMap resources() {
        return new ZenResourceMap(value.resources);
    }

    @ZenGetter("required")
    public ZenResourceMap required() {
        return new ZenResourceMap(value.required);
    }

    @ZenGetter("researchRates")
    public ZenResourceMap researchRates() {
        return new ZenResourceMap(value.researchRates);
    }

    @ZenGetter("concurrency")
    public long concurrency() {
        return value.concurrency;
    }

    @ZenSetter("concurrency")
    public void concurrency(long amount) {
        value.concurrency = amount;
    }

    @ZenGetter("energyMultiplier")
    public double energyMultiplier() {
        return value.energyMultiplier;
    }

    @ZenSetter("energyMultiplier")
    public void energyMultiplier(double amount) {
        value.energyMultiplier = amount;
    }
}
