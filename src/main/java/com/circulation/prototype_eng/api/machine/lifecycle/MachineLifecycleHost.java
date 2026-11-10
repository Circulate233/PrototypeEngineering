package com.circulation.prototype_eng.api.machine.lifecycle;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * World access supplied by a controller host. External controller implementations may supply this
 * contract directly. Lifecycle callbacks use it only on the server thread, never on recipe workers.
 */
public interface MachineLifecycleHost {
    MachineIdentity identity();

    /**
     * Original physical controller block; opaque or non-block hosts keep absence unprovable.
     */
    @Nullable
    default ResourceLocation controllerBlockId() {
        return null;
    }

    World world();

    /**
     * Stable world-object identity for registries; custom non-Forge hosts provide an equivalent token.
     */
    default Object worldIdentity() {
        return world();
    }

    /**
     * Trusted real placement player, including a serialized item; absent for FakePlayer/non-player loading.
     */
    @SuppressWarnings("unused") // Downstream lifecycle listeners consume the bound placement actor.
    @Nullable
    default UUID placementPlayer() {
        return null;
    }

    default MinecraftServer server() {
        return Objects.requireNonNull(world().getMinecraftServer(), "Machine host has no server");
    }

    /**
     * A host with a different execution environment must enforce its equivalent owner-thread rule.
     */
    default void requireServerThread() {
        if (world().isRemote || !server().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Machine lifecycle requires its owning server thread");
        }
    }
}
