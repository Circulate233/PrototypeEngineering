package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.network.NetworkParticipants;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.integration.ftb.FtbOwnerTeamResolver;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Session-wide network publication, refreshed only at scheduler owner boundaries.
 */
public final class ServerNetworkRuntime {
    @Nullable
    private static volatile Session session;

    private ServerNetworkRuntime() {
    }

    public static synchronized void start(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        if (session != null) {
            throw new IllegalStateException("A server network runtime is already running");
        }
        var identities = ServerMachineIdentities.forServer(server);
        session = new Session(server, new NetworkRuntime(identities, NetworkResourceRegistry.INSTANCE,
            new FtbOwnerTeamResolver(server), () -> requireThread(server)));
    }

    public static NetworkRuntime forServer(MinecraftServer server) {
        var current = session;
        if (current == null || current.server != server) {
            throw new IllegalStateException("No network runtime for this server session");
        }
        return current.runtime;
    }

    /** Attach external devices to the same session and budgets used by the machine scheduler. */
    @SuppressWarnings("resource") // Server stop owns the borrowed runtime.
    public static NetworkParticipants participants(MinecraftServer server) {
        requireThread(server);
        return forServer(server).participants();
    }

    /**
     * Scheduler shutdown must first prove all accepted work has exited.
     */
    public static synchronized void stop() {
        var current = session;
        if (current == null) {
            return;
        }
        requireThread(current.server);
        current.runtime.close();
        session = null;
    }

    private static void requireThread(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Network runtime requires its server thread");
        }
    }

    private record Session(MinecraftServer server, NetworkRuntime runtime) {
    }
}
