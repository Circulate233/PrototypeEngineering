package com.circulation.prototype_eng.storage;

import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.structure.reload.StructureNetwork;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Server-owner publication to inventory viewers, independent of any loaded hatch tile. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerHatchConfigurations {
    private static volatile @Nullable Session session;

    private ServerHatchConfigurations() {
    }

    public static void start(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread() || session != null)
            throw new IllegalStateException("Invalid hatch display session start");
        session = new Session(server, ServerTaskSchedulers.forServer(server), UUID.randomUUID());
    }

    public static void stop() {
        requireConfigurationOwner();
        session = null;
    }

    public static void requireConfigurationOwner() {
        var current = session;
        if (current != null && !current.server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Live hatch targets require the exact server owner");
    }

    public static void changed() {
        requireConfigurationOwner();
        var current = session;
        if (current == null || current.queued) return;
        current.queued = true;
        current.scheduler.runAtBoundary(() -> {
            current.queued = false;
            if (!current.current()) return;
            var snapshot = HatchConfig.snapshot(current.id);
            byte[] payload = snapshot.encode();
            for (var player : current.server.getPlayerList().getPlayers())
                StructureNetwork.sendHatchConfiguration(snapshot, payload, player);
        });
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        var current = session;
        if (!(event.player instanceof EntityPlayerMP player) || current == null || player.getServer() != current.server)
            return;
        var connection = player.connection;
        current.scheduler.runAtBoundary(() -> {
            if (!current.current() || player.connection != connection
                || current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) return;
            var snapshot = HatchConfig.snapshot(current.id);
            StructureNetwork.sendHatchConfiguration(snapshot, snapshot.encode(), player);
        });
    }

    private static final class Session {
        private final MinecraftServer server;
        private final TaskScheduler scheduler;
        private final UUID id;
        private boolean queued;

        private Session(MinecraftServer server, TaskScheduler scheduler, UUID id) {
            this.server = server;
            this.scheduler = scheduler;
            this.id = id;
        }

        private boolean current() {
            return session == this && !scheduler.status().closing() && !scheduler.status().closed();
        }
    }
}
