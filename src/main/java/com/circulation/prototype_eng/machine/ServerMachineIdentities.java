package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;


/**
 * Identity registrations belong to one actual server session, never to a saved UUID.
 */
public final class ServerMachineIdentities {
    @Nullable
    private static volatile Session session;

    private ServerMachineIdentities() {
    }

    public static synchronized void start(MinecraftServer server) {
        requireThread(server);
        if (session != null) {
            throw new IllegalStateException("A machine identity session is already running");
        }
        session = new Session(server, new MachineIdentityRegistry(() -> requireThread(server)));
    }

    public static MachineIdentityRegistry forServer(MinecraftServer server) {
        var current = session;
        if (current == null || current.server != server) {
            throw new IllegalStateException("No identity registry for this server session");
        }
        return current.registry;
    }

    /**
     * Called only after scheduler shutdown has proved all accepted work has physically exited.
     */
    public static synchronized void stop() {
        var current = session;
        if (current == null) {
            return;
        }
        requireThread(current.server);
        current.registry.close();
        session = null;
    }

    private static void requireThread(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Identity session requires its server thread");
        }
    }

    private record Session(MinecraftServer server, MachineIdentityRegistry registry) {
    }
}
