package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarations;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionPublicationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.network.InitNetwork;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.Objects;

/**
 * Independent transport subscriber to C's exact server owner publication and verified login context.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerRecipeManifests {
    private static volatile @Nullable Current current;
    private static volatile @Nullable Status status;

    static {
        RecipeDeclarations.INSTANCE.onRegistered((_, snapshot) -> {
            var captured = current;
            if (captured == null || captured.snapshot != snapshot) return;
            captured.server.addScheduledTask(() -> {
                if (current != captured) return;
                try {
                    send(captured, null, false);
                } catch (RuntimeException | Error failure) {
                    PrototypeEngineering.LOGGER.error("Delayed recipe declaration manifest failed", failure);
                }
            });
        });
    }

    private ServerRecipeManifests() {
    }

    @SubscribeEvent
    public static void published(RecipeDefinitionPublicationEvent event) {
        if (!event.server().isCallingFromMinecraftThread())
            throw new IllegalStateException("Recipe manifest requires server owner");
        if (!ServerRecipeDefinitions.current(event.server(), event.sessionId(), event.current())) return;
        var previous = current;
        if (previous != null && previous.server == event.server() && previous.session.equals(event.sessionId())) {
            if (previous.snapshot.generation() > event.current().generation()) return;
            if (previous.snapshot == event.current()) {
                send(previous, event.player(), true);
                return;
            }
        }
        var next = new Current(event.server(), event.sessionId(), event.current());
        current = next;
        send(next, event.player(), true);
    }

    public static void stop() {
        current = null;
        status = null;
    }

    public static @Nullable Status status() {
        return status;
    }

    private static void send(Current captured, @Nullable EntityPlayerMP recipient, boolean announce) {
        if (current != captured || !ServerRecipeDefinitions.current(captured.server, captured.session, captured.snapshot))
            return;
        status = new Status(captured.session, captured.snapshot.generation(), false, "Preparing recipe declaration manifest");
        if (announce) deliver(captured, recipient, null, null);
        var key = RecipeDeclarations.INSTANCE.key(captured.snapshot);
        if (key == null) {
            status = new Status(captured.session, captured.snapshot.generation(), false, "Missing complete explicit recipe declaration key");
            PrototypeEngineering.LOGGER.warn("Recipe manifest deferred for session {} generation {}: missing declaration key",
                captured.session, captured.snapshot.generation());
            return;
        }
        RecipeDefinitionManifest manifest;
        byte[] payload;
        try {
            manifest = RecipeDefinitionManifest.capture(captured.session, key, captured.snapshot);
            payload = RecipeDefinitionManifestCodec.encode(manifest);
        } catch (RuntimeException | Error failure) {
            status = new Status(captured.session, captured.snapshot.generation(), false,
                "Recipe declaration manifest preparation failed: " + failure.getMessage());
            throw failure;
        }
        if (current != captured || !ServerRecipeDefinitions.current(captured.server, captured.session, captured.snapshot))
            return;
        deliver(captured, recipient, manifest, payload);
        status = new Status(captured.session, captured.snapshot.generation(), true, "Manifest sent; client readiness is independent");
    }

    private static void deliver(Current captured, @Nullable EntityPlayerMP recipient,
                                @Nullable RecipeDefinitionManifest manifest, byte @Nullable [] payload) {
        if (recipient != null) {
            sendTo(captured, recipient, manifest, payload);
            return;
        }
        for (var player : captured.server.getPlayerList().getPlayers()) sendTo(captured, player, manifest, payload);
    }

    private static void sendTo(Current captured, EntityPlayerMP player,
                               @Nullable RecipeDefinitionManifest manifest, byte @Nullable [] payload) {
        if (manifest == null)
            InitNetwork.announceRecipeDefinitions(captured.session, captured.snapshot.generation(),
                captured.snapshot.generation() != 0 || RecipeDeclarations.INSTANCE.key(captured.snapshot) != null, player);
        else InitNetwork.sendRecipeDefinitions(manifest, Objects.requireNonNull(payload), player);
    }

    private record Current(MinecraftServer server, UUID session, RecipeRegistry.Snapshot snapshot) {
    }

    /**
     * Server sent/deferred status makes no claim about any client's native display readiness.
     */
    public record Status(UUID serverSession, long generation, boolean manifestSent, String reason) {
    }
}
