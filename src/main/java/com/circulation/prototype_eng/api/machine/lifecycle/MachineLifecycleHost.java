package com.circulation.prototype_eng.api.machine.lifecycle;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.util.Objects;

/**
 * World access supplied by a controller host. External controller implementations may supply this
 * contract directly. Lifecycle callbacks use it only on the server thread, never on recipe workers.
 */
public interface MachineLifecycleHost {
    MachineIdentity identity();
    World world();

    default MinecraftServer server() {
        return Objects.requireNonNull(world().getMinecraftServer(), "Machine host has no server");
    }

    /** A host with a different execution environment must enforce its equivalent owner-thread rule. */
    default void requireServerThread() {
        if (world().isRemote || !server().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Machine lifecycle requires its owning server thread");
        }
    }
}
