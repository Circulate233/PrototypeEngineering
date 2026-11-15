package com.circulation.prototype_eng.api.recipe;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Owner-thread notification of the exact server directory or a verified login recipient. Declaration
 * resolution and manifest transport subscribe separately; receiving this event does not mean JEI applied it.
 */
public final class RecipeDefinitionPublicationEvent extends Event {
    private final MinecraftServer server;
    private final UUID sessionId;
    @Nullable
    private final RecipeRegistry.Snapshot previous;
    private final RecipeRegistry.Snapshot current;
    @Nullable
    private final EntityPlayerMP player;

    public RecipeDefinitionPublicationEvent(MinecraftServer server, UUID sessionId, @Nullable RecipeRegistry.Snapshot previous,
                                            RecipeRegistry.Snapshot current, @Nullable EntityPlayerMP player) {
        this.server = server;
        this.sessionId = sessionId;
        this.previous = previous;
        this.current = current;
        this.player = player;
    }

    public MinecraftServer server() {
        return server;
    }

    public UUID sessionId() {
        return sessionId;
    }

    @Nullable
    public RecipeRegistry.Snapshot previous() {
        return previous;
    }

    public RecipeRegistry.Snapshot current() {
        return current;
    }

    @Nullable
    public EntityPlayerMP player() {
        return player;
    }
}
