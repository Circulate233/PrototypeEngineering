package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One scheduler per actual server session, including integrated-server restarts in the same JVM.
 * FML lifecycle owns creation/closure; END ticks run on the owning server thread and join workers.
 * This service has no static executors and never restarts an old scheduler on registration.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerTaskSchedulers {
    @Nullable private static volatile Session session;

    private ServerTaskSchedulers() { }

    public static synchronized void start(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isCallingFromMinecraftThread()) { throw new IllegalStateException("Scheduler startup requires the server thread"); }
        if (session != null) { throw new IllegalStateException("A server scheduler is already running"); }
        var settings = SchedulerConfig.settings();
        session = new Session(server, new TaskScheduler(Thread.currentThread(), settings.workerThreads(), settings.laneCount()));
    }

    /** Exact server identity prevents an old world's callbacks from accessing a new session. */
    @SuppressWarnings("unused") // Public registration/submission entry for controller hosts and other addons.
    public static TaskScheduler forServer(MinecraftServer server) {
        var current = session;
        if (current == null || current.server != server) { throw new IllegalStateException("No scheduler for this server session"); }
        return current.scheduler;
    }

    /** Called after server worlds have unloaded, so their controllers can unregister first. */
    public static synchronized void stop() {
        var current = session;
        if (current == null) { return; }
        if (!current.server.isCallingFromMinecraftThread()) { throw new IllegalStateException("Scheduler shutdown requires the server thread"); }
        current.scheduler.close();
        session = null;
    }

    @SubscribeEvent
    @SuppressWarnings("resource") // FML serverStopped owns closure of this session-wide scheduler.
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        var current = session;
        if (current != null) {
            var network = ServerNetworkRuntime.forServer(current.server);
            long tick = current.nextTick;
            current.nextTick = Math.addExact(tick, 1);
            network.beginTick(tick);
            try { current.scheduler.tick(network::refresh); }
            finally { network.endTick(); }
        }
    }

    private static final class Session {
        private final MinecraftServer server;
        private final TaskScheduler scheduler;
        // Advances once per server END event, never once per task or network capture.
        private long nextTick;

        private Session(MinecraftServer server, TaskScheduler scheduler) {
            this.server = server;
            this.scheduler = scheduler;
        }
    }
}
