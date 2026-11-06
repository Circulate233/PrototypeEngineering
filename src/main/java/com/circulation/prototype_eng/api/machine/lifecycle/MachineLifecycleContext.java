package com.circulation.prototype_eng.api.machine.lifecycle;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.util.Objects;

/** Frozen identity and formed value at a synchronous event; world access must not escape to workers. */
public record MachineLifecycleContext(MachineInstance machine, MachineIdentity identity,
                                      MachineLifecycleHost host, boolean formed) {
    public MachineLifecycleContext {
        Objects.requireNonNull(machine);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(host);
    }

    public World world() { return host.world(); }
    public MinecraftServer server() { return host.server(); }
}
