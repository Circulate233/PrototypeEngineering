package com.circulation.prototype_eng.storage;

import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;

/** Capability instances belong to hosts, which also own their save format. */
public final class HatchCapabilityRegistration {
    private HatchCapabilityRegistration() {
    }

    public static void register() {
        registerHostCapability(ResourceHatch.class);
        registerHostCapability(EnergyInput.class);
    }

    private static <T> void registerHostCapability(Class<T> type) {
        CapabilityManager.INSTANCE.register(type, new HostStorage<>(), () -> {
            throw new UnsupportedOperationException("Hatch capabilities require a host");
        });
    }

    private static final class HostStorage<T> implements Capability.IStorage<T> {
        @Override
        public NBTBase writeNBT(Capability<T> capability, T instance, EnumFacing side) {
            throw new UnsupportedOperationException("The hatch host owns persistence");
        }

        @Override
        public void readNBT(Capability<T> capability, T instance, EnumFacing side, NBTBase data) {
            throw new UnsupportedOperationException("The hatch host owns persistence");
        }
    }
}
