package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Team device snapshot before publication. Lists are read-only; do not retain them across publications or mutate devices.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record NetworkData(String teamId, long tick, List<Device> devices, List<NetworkContribution> contributions) {
    public boolean hasDevice(ResourceLocation type) {
        for (int index = 0; index < devices.size(); index++) {
            var device = devices.get(index);
            if (device.location().machineType().equals(type) && device.snapshot().formed()) return true;
        }
        return false;
    }

    public record Device(MachineIdentity location, NetworkDeviceSnapshot snapshot) {
    }
}
