package com.circulation.prototype_eng.integration.crafttweaker.hatch;

import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.storage.HatchConfigurationUpdates;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.server.IServer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;

/**
 * Values only; startup registration and loaded propagation use the same public Java configuration API.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.hatch.Configuration")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public record ZenHatchConfiguration(HatchConfigurationUpdates.Target value) {

    @ZenMethod
    public static ZenHatchConfiguration resources(int tier, @Nullable ZenResourceHatchSide input, @Nullable ZenResourceHatchSide output) {
        return new ZenHatchConfiguration(new HatchConfigurationUpdates.Target(HatchTier.of(tier),
            new ResourceHatchConfiguration(input == null ? null : input.value(), output == null ? null : output.value())));
    }

    @ZenMethod
    public static ZenHatchConfiguration energy(int tier, @Nullable ZenEnergyHatchSide input, @Nullable ZenEnergyHatchSide output) {
        return new ZenHatchConfiguration(new HatchConfigurationUpdates.Target(HatchTier.of(tier),
            new EnergyHatchConfiguration(input == null ? null : input.value(), output == null ? null : output.value())));
    }

    @ZenMethod
    public static void configureDefaults(ZenHatchConfiguration[] configurations) {
        var targets = targets(configurations);
        DefinitionRegistrations.runtime(_ -> HatchConfigurationUpdates.configureDefaults(targets));
    }

    @ZenMethod
    public static ZenHatchUpdate request(IServer server, ZenHatchConfiguration[] configurations) {
        return new ZenHatchUpdate(HatchConfigurationUpdates.request(CraftTweakerMC.getMCServer(server),
            targets(configurations)));
    }

    private static List<HatchConfigurationUpdates.Target> targets(ZenHatchConfiguration[] configurations) {
        var targets = new ObjectArrayList<HatchConfigurationUpdates.Target>(configurations.length);
        for (var configuration : configurations) targets.add(configuration.value());
        return targets;
    }
}
