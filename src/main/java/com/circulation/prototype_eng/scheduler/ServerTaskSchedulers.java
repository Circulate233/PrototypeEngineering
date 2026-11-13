package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import com.circulation.prototype_eng.research.ServerResearch;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * One scheduler per actual server session, including integrated-server restarts in the same JVM.
 * FML lifecycle owns creation/closure; END ticks run on the owning server thread and join workers.
 * This service has no static executors and never restarts an old scheduler on registration.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerTaskSchedulers {
    @Nullable
    private static volatile Session session;

    private ServerTaskSchedulers() {
    }

    public static synchronized void start(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Scheduler startup requires the server thread");
        }
        if (session != null) {
            throw new IllegalStateException("A server scheduler is already running");
        }
        var settings = SchedulerConfig.settings();
        session = new Session(server, settings);
    }

    /**
     * Exact server identity prevents an old world's callbacks from accessing a new session.
     */
    @SuppressWarnings("unused") // Public registration/submission entry for controller hosts and other addons.
    public static TaskScheduler forServer(MinecraftServer server) {
        var current = session;
        if (current == null || current.server != server) {
            throw new IllegalStateException("No scheduler for this server session");
        }
        return current.scheduler;
    }

    /**
     * Reads the configured file and queues settings for this exact server session.
     */
    @SuppressWarnings("resource") // The lifecycle owns this borrowed scheduler.
    public static SchedulerConfig.Settings requestReloadFromConfig(MinecraftServer server) {
        var scheduler = forServer(server);
        if (!server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Scheduler reload requires the server thread");
        var requested = SchedulerConfig.reload();
        scheduler.requestSettings(requested);
        return requested;
    }

    /**
     * Called after server worlds have unloaded, so their controllers can unregister first.
     */
    public static synchronized void stop() {
        var current = session;
        if (current == null) {
            return;
        }
        if (!current.server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Scheduler shutdown requires the server thread");
        }
        current.scheduler.close();
        session = null;
    }

    /**
     * FML serverStopping precedes native final world saving. Mark eligibility without scheduling a
     * callback that could remain queued behind retained cleanup, and without serializing active data.
     * The actual controller codecs still require their complete persistence boundary when saving.
     */
    public static void markControllerDataDirty() {
        var current = session;
        if (current == null) return;
        if (!current.server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Controller data save marking requires its server owner");
        for (var world : current.server.worlds) {
            if (world == null || world.getMinecraftServer() != current.server) continue;
            for (var tile : new ObjectArrayList<>(world.loadedTileEntityList))
                if (tile instanceof MachineControllerBlockEntity controller && controller.getWorld() == world)
                    controller.markControllerDataDirty(false);
        }
    }

    /**
     * One tick's native save work only; the tile's original tick guard prevents duplicate updates.
     */
    public static void queueControllerDataDirty(MinecraftServer server, MachineControllerBlockEntity controller) {
        var current = session;
        if (current == null || current.server != server)
            throw new IllegalStateException("No controller data save queue for this server session");
        if (!server.isCallingFromMinecraftThread() || controller.getWorld() == null || controller.getWorld().getMinecraftServer() != server)
            throw new IllegalStateException("Controller data save queue requires its exact server owner");
        current.dirtyControllers.add(controller);
    }

    private static void markTickControllerDataDirty(Session current) {
        if (session != current || !current.server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Controller data save batch requires its exact server owner");
        var batch = current.dirtyControllers;
        current.dirtyControllers = new ObjectArrayList<>();
        // Comparator callbacks may enqueue another tile. They must not append to this frozen pass.
        for (var controller : batch) {
            var world = controller.getWorld();
            if (world != null && world.getMinecraftServer() == current.server) controller.markControllerDataDirty(true);
        }
    }

    @SubscribeEvent
    @SuppressWarnings("resource") // FML serverStopped owns closure of this session-wide scheduler.
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var current = session;
        if (current != null) {
            var network = ServerNetworkRuntime.forServer(current.server);
            long tick = current.nextTick;
            current.nextTick = Math.addExact(tick, 1);
            network.beginTick(tick);
            current.currentTick = tick;
            current.tickOpen = true;
            current.dataCentersSynced = false;
            try {
                current.scheduler.tick(current::prepareNetwork);
            } finally {
                current.tickOpen = false;
                try {
                    network.endTick();
                } finally {
                    // Native autosave ran before this END event and may have cleared chunk dirty.
                    // The scheduler has physically joined this tick's accepted workers before return.
                    markTickControllerDataDirty(current);
                }
            }
        }
    }

    private static final class Session {
        private final MinecraftServer server;
        private final TaskScheduler scheduler;
        // Advances once per server END event, never once per task or network capture.
        private long nextTick;
        private long currentTick;
        private boolean tickOpen;
        private boolean dataCentersSynced;
        private ObjectArrayList<MachineControllerBlockEntity> dirtyControllers = new ObjectArrayList<>();

        @SuppressWarnings("resource") // The FML network service owns this borrowed session runtime.
        private Session(MinecraftServer server, SchedulerConfig.Settings settings) {
            this.server = server;
            var factory = new MachineRecipeExecutionFactory(this::candidates,
                () -> () -> ServerNetworkRuntime.forServer(server).refresh(), () -> currentTick, () -> tickOpen, Session::networkMachine);
            scheduler = new TaskScheduler(Thread.currentThread(), settings, factory,
                (tasks, deadline) -> ServerResearch.prepareTick(server, tasks, deadline));
        }

        private static boolean networkMachine(MachineInstance machine) {
            for (var binding : machine.components().assemblyBindings()) {
                if (binding.component() instanceof NetworkRecipeWork) {
                    return true;
                }
            }
            return false;
        }

        private List<TaskScheduler.PlannedCandidate> candidates() {
            return scheduler.plannedCandidates();
        }

        @SuppressWarnings("resource") // Only the server lifecycle closes the borrowed network runtime.
        private void prepareNetwork() {
            if (dataCentersSynced) ServerNetworkRuntime.forServer(server).refresh();
            else {
                ServerResearch.refreshDataCenters(server);
                dataCentersSynced = true;
            }
        }
    }
}
