package com.circulation.prototype_eng.api.machine.lifecycle;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/** Location of a placed controller; this is identity, never a saved execution permission. */
public record MachineIdentity(ResourceLocation machineType, int dimension, BlockPos controllerPos) {
    public MachineIdentity {
        Objects.requireNonNull(machineType);
        controllerPos = Objects.requireNonNull(controllerPos).toImmutable();
    }
}
