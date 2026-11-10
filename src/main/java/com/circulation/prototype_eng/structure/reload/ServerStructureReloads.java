package com.circulation.prototype_eng.structure.reload;

import com.circulation.prototype_eng.network.InitNetwork;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.structure.ReloadableStructureHost;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistry;
import com.circulation.prototype_eng.machine.ControllerStructureUpdates;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.structure.json.ForgeStructureBlocks;
import com.circulation.prototype_eng.structure.json.StructureJsonLoader;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One exact server session; no executor, global mutable structure table, or PN identity deduplication.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerStructureReloads {
    @Nullable
    private static volatile Session session;

    private ServerStructureReloads() {
    }

    public static void start(MinecraftServer server, Path directory, StructureCatalog javaDefinitions, StructurePredicateRegistry factories) {
        if (!server.isCallingFromMinecraftThread() || session != null)
            throw new IllegalStateException("Structure session start requires an unused server owner");
        var scheduler = ServerTaskSchedulers.forServer(server);
        var coordinator = new StructureReloadCoordinator(scheduler, MachineTypeRegistry.INSTANCE,
            () -> readCandidate(server, directory, javaDefinitions, factories),
            publication -> {
                var current = session;
                if (current != null && current.server == server) {
                    current.publishedView = publication.view();
                    // Keep the exact input object; a same-ID foreign runtime replacement cannot acquire this view.
                    current.publishedCatalog = current.candidateCatalog;
                    current.candidateCatalog = null;
                }
                for (var player : server.getPlayerList().getPlayers()) InitNetwork.send(publication, player);
            });
        var subscription = MachineLifecycleRegistry.GLOBAL.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                if (event.context().host() instanceof ReloadableStructureHost host) {
                    var current = session;
                    if (current != null && current.server == server)
                        coordinator.loaded(event.context().machine(), host);
                }
            }

            @Override
            public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
                var lifecycle = Objects.requireNonNull(event.context().machine().lifecycle());
                coordinator.unloaded(lifecycle);
            }
        });
        session = new Session(server, scheduler, coordinator, subscription);
    }

    private static StructureCatalog readCandidate(MinecraftServer server, Path directory, StructureCatalog javaDefinitions,
                                                  StructurePredicateRegistry factories) {
        var candidate = StructureJsonLoader.readDirectory(directory).resolve(javaDefinitions, factories, new ForgeStructureBlocks());
        requireSession(server).candidateCatalog = candidate;
        return candidate;
    }

    /** Exact owner query; absent session, replacement interval or foreign catalog has no usable display generation. */
    public static @Nullable StructureCatalogView view(MinecraftServer server, StructureCatalog expected) {
        if (!server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Structure display view requires its server owner");
        var current = session;
        if (current == null || current.server != server || current.publishedCatalog != expected ||
            MachineTypeRegistry.INSTANCE.structureCatalog() != expected || current.coordinator.publication().view() != current.publishedView)
            return null;
        return current.publishedView;
    }

    /**
     * Every actual ControllerStructureUpdates construction joins this path, including external wrappers.
     */
    @SuppressWarnings("resource") // The session coordinator owns the retained registration handle.
    public static void attach(TaskScheduler scheduler, ControllerStructureUpdates updates) {
        var current = session;
        // A live server has one joined structure owner. Foreign schedulers must fail before
        // construction returns; silently ignoring them would allow a second automatic owner.
        if (current != null) {
            if (updates.scheduler() != scheduler)
                throw new IllegalArgumentException("Structure attachment differs from its updates scheduler");
            current.coordinator.register(updates);
        }
    }

    public static void detach(TaskScheduler scheduler, ControllerStructureUpdates updates) {
        var current = session;
        if (current != null && current.scheduler == scheduler) current.coordinator.unregister(updates);
    }

    /**
     * Reuse the exact automatically enrolled update owner instead of creating another matcher.
     */
    @SuppressWarnings("unused") // Public external-host entry for reusing the automatically enrolled owner.
    public static @Nullable ControllerStructureUpdates updates(MinecraftServer server, MachineLifecycle lifecycle) {
        return requireSession(server).coordinator.updates(lifecycle);
    }

    public static void reload(MinecraftServer server, Consumer<StructureReloadCoordinator.Result> completed) {
        var current = requireSession(server);
        current.coordinator.reload(completed);
    }

    /**
     * Custom lifecycle registries can deliver their actual Loaded event through this same path.
     */
    public static void loaded(MinecraftServer server, MachineInstance machine, ReloadableStructureHost host) {
        requireSession(server).coordinator.loaded(machine, host);
    }

    public static void stop() {
        var current = session;
        if (current == null) return;
        if (!current.server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Structure session stop requires its server owner");
        // Call only after ServerTaskSchedulers.stop() proves termination and drains accepted boundaries.
        current.coordinator.close();
        current.subscription.close();
        session = null;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        var current = session;
        if (current != null) {
            current.tick = Math.incrementExact(current.tick);
            current.coordinator.tickLoaded(current.tick);
        }
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) return;
        var server = Objects.requireNonNull(player.getServer());
        var current = requireSession(server);
        var connection = player.connection;
        current.scheduler.runAtBoundary(() -> {
            if (session == current && player.connection == connection && server.getPlayerList().getPlayerByUUID(player.getUniqueID()) == player) {
                current.coordinator.enrollLoaded();
                InitNetwork.send(current.coordinator.publication(), player);
            }
        });
    }

    private static Session requireSession(MinecraftServer server) {
        var current = session;
        if (current == null || current.server != server)
            throw new IllegalStateException("No structure service for this server session");
        return current;
    }

    private static final class Session {
        private final MinecraftServer server;
        private final TaskScheduler scheduler;
        private final StructureReloadCoordinator coordinator;
        private final MachineLifecycleRegistry.Subscription subscription;
        @Nullable
        private StructureCatalog candidateCatalog;
        @Nullable
        private StructureCatalog publishedCatalog;
        private StructureCatalogView publishedView;
        private long tick;

        private Session(MinecraftServer server, TaskScheduler scheduler, StructureReloadCoordinator coordinator, MachineLifecycleRegistry.Subscription subscription) {
            this.server = server;
            this.scheduler = scheduler;
            this.coordinator = coordinator;
            this.subscription = subscription;
            publishedCatalog = MachineTypeRegistry.INSTANCE.structureCatalog();
            publishedView = coordinator.publication().view();
        }
    }
}
