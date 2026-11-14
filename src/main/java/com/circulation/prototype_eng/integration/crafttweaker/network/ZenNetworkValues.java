package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.network.NetworkResourceState;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;

@ZenRegister
@ZenClass("mods.prototype_eng.network.Values")
@SuppressWarnings("unused")
public final class ZenNetworkValues {
    private final NetworkResourceState value;
    private final ZenResourceMap resources;
    private final ZenResourceMap required;
    private final ZenResourceMap researchRates;

    public ZenNetworkValues(NetworkResourceState value) {
        this.value = value;
        resources = new ZenResourceMap(value.resources);
        required = new ZenResourceMap(value.required);
        researchRates = new ZenResourceMap(value.researchRates);
    }

    public NetworkResourceState value() {
        return value;
    }

    @ZenGetter("resources")
    public ZenResourceMap resources() {
        return resources;
    }

    @ZenGetter("required")
    public ZenResourceMap required() {
        return required;
    }

    @ZenGetter("researchRates")
    public ZenResourceMap researchRates() {
        return researchRates;
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
