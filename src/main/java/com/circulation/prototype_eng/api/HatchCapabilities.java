package com.circulation.prototype_eng.api;

import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import org.jetbrains.annotations.Nullable;

/**
 * Public Forge discovery points. Third-party block entities can expose these
 * capabilities without inheriting a Prototype Engineering block entity.
 */
public final class HatchCapabilities {
    @CapabilityInject(ResourceHatch.class)
    public static Capability<ResourceHatch> RESOURCE;

    @CapabilityInject(EnergyInput.class)
    public static Capability<EnergyInput> ENERGY_INPUT;

    @CapabilityInject(EnergyOutput.class)
    public static Capability<EnergyOutput> ENERGY_OUTPUT;

    private HatchCapabilities() {
    }

    @Nullable
    public static ResourceHatch findResource(@Nullable TileEntity host, @Nullable EnumFacing side) {
        if (host == null || host.isInvalid() || RESOURCE == null) {
            return null;
        }
        ResourceHatch hatch = host.getCapability(RESOURCE, side);
        return hatch != null && hatch.isValid() ? hatch : null;
    }

    @Nullable
    public static EnergyInput findEnergyInput(@Nullable TileEntity host, @Nullable EnumFacing side) {
        if (host == null || host.isInvalid() || ENERGY_INPUT == null) {
            return null;
        }
        EnergyInput input = host.getCapability(ENERGY_INPUT, side);
        return input != null && input.isValid() ? input : null;
    }

    @Nullable
    public static EnergyOutput findEnergyOutput(@Nullable TileEntity host, @Nullable EnumFacing side) {
        if (host == null || host.isInvalid() || ENERGY_OUTPUT == null) {
            return null;
        }
        EnergyOutput output = host.getCapability(ENERGY_OUTPUT, side);
        return output != null && output.isValid() ? output : null;
    }
}
