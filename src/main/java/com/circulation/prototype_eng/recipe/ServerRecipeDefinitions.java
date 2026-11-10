package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionPublicationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionUpdates;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** Exact server lifecycle owner of recipe definition publication; no executor or parallel registry. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerRecipeDefinitions {
    @Nullable private static volatile Session session;

    private ServerRecipeDefinitions() {}

    public static synchronized void start(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        if (session != null) throw new IllegalStateException("Recipe definition session is already running");
        var scheduler = ServerTaskSchedulers.forServer(server);
        var sessionId = UUID.randomUUID();
        var updates = new RecipeDefinitionUpdates(RecipeRegistry.INSTANCE, NetworkRecipeExtensions.INSTANCE, scheduler,
            sessionId, notice -> published(server, notice));
        var started = new Session(server, scheduler, updates, sessionId);
        session = started;
        scheduler.runAtBoundary(() -> {
            if (session != started || !updates.accepting()) return;
            try {
                MinecraftForge.EVENT_BUS.post(new RecipeDefinitionPublicationEvent(server, sessionId, null, updates.snapshot(), null));
            } catch (RuntimeException | Error failure) {
                PrototypeEngineering.LOGGER.error("Recipe definition session notification failed", failure);
            }
        });
    }

    public static RecipeDefinitionUpdates forServer(MinecraftServer server) {
        var current = requireSession(server);
        if (!current.updates.accepting()) throw new IllegalStateException("Recipe definition session is stopping");
        return current.updates;
    }

    /** Pure late-notification proof; a stopped or replaced session is an ordinary false result. */
    public static boolean current(MinecraftServer server, UUID sessionId, RecipeRegistry.Snapshot snapshot) {
        var current = session;
        return current != null && current.server == server && current.sessionId.equals(sessionId)
            && current.updates.accepting() && current.updates.snapshot() == snapshot;
    }

    public static void stopping() {
        var current = session;
        if (current == null) return;
        requireThread(current.server);
        current.updates.stopAccepting();
    }

    /** Called after ServerTaskSchedulers.stop, including failed startup cleanup. */
    public static synchronized void stop() {
        var current = session;
        if (current == null) return;
        requireThread(current.server);
        current.updates.close();
        session = null;
    }

    private static void published(MinecraftServer server, RecipeDefinitionUpdates.Published notice) {
        var current = requireSession(server);
        if (!current.sessionId.equals(notice.sessionId()) || current.updates.snapshot() != notice.current()) {
            throw new IllegalStateException("Obsolete recipe definition notification");
        }
        MinecraftForge.EVENT_BUS.post(new RecipeDefinitionPublicationEvent(server, current.sessionId, notice.previous(), notice.current(), null));
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) return;
        var server = Objects.requireNonNull(player.getServer());
        var current = requireSession(server);
        var connection = player.connection;
        current.scheduler.runAtBoundary(() -> {
            if (session != current || !current.updates.accepting() || !current.sessionId.equals(current.updates.sessionId())
                || player.connection != connection || server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) return;
            try {
                MinecraftForge.EVENT_BUS.post(new RecipeDefinitionPublicationEvent(server, current.sessionId, null, current.updates.snapshot(), player));
            } catch (RuntimeException | Error failure) {
                PrototypeEngineering.LOGGER.error("Recipe definition login notification failed for {}", player.getUniqueID(), failure);
            }
        });
    }

    private static Session requireSession(MinecraftServer server) {
        var current = session;
        if (current == null || current.server != server) throw new IllegalStateException("No recipe definition owner for this server session");
        return current;
    }

    private static void requireThread(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread()) throw new IllegalStateException("Recipe definition lifecycle requires its server owner");
    }

    private record Session(MinecraftServer server, TaskScheduler scheduler, RecipeDefinitionUpdates updates, UUID sessionId) {}
}
