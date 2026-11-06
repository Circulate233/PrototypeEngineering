package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import java.util.Objects;

/** Binds an already attached server-side controller without registering a block or a scheduler. */
@SuppressWarnings("unused") // Public adapter for downstream controller tile entities.
public final class ControllerLifecycleHost implements MachineLifecycleHost {
    private final TileEntity controller;
    private final MachineIdentity identity;

    public ControllerLifecycleHost(ResourceLocation machineType, TileEntity controller) {
        this.controller = Objects.requireNonNull(controller);
        identity = new MachineIdentity(machineType, world().provider.getDimension(), controller.getPos());
        requireServerThread();
    }

    @Override public MachineIdentity identity() { return identity; }

    @Override public World world() {
        return Objects.requireNonNull(controller.getWorld(), "Machine controller has no world");
    }

    @Override public void requireServerThread() {
        MachineLifecycleHost.super.requireServerThread();
        if (world().provider.getDimension() != identity.dimension() || !controller.getPos().equals(identity.controllerPos())) {
            throw new IllegalStateException("Machine controller changed its bound location");
        }
    }
}
