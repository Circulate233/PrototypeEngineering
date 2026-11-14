package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * 本次发布前的同队设备快照。列表只读，禁止跨发布周期保留或修改其中设备。
 */
public record NetworkData(String teamId, long tick, List<Device> devices, List<NetworkContribution> contributions) {
    public boolean hasDevice(ResourceLocation type) {
        for (var device : devices)
            if (device.location().machineType().equals(type) && device.snapshot().formed()) return true;
        return false;
    }

    public record Device(MachineIdentity location, NetworkDeviceSnapshot snapshot) {
    }
}
