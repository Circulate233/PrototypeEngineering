package com.circulation.prototype_eng.integration.crafttweaker.hatch;

import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;


@ZenRegister
@ZenClass("mods.prototype_eng.hatch.EnergySide")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public record ZenEnergyHatchSide(EnergyHatchConfiguration.Side value) {

    @ZenMethod
    public static ZenEnergyHatchSide create(long capacity, long transfer) {
        return new ZenEnergyHatchSide(new EnergyHatchConfiguration.Side(capacity, transfer));
    }
}
