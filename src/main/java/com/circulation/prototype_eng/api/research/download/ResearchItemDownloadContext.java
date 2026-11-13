package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Objects;
import java.util.UUID;

/**
 * Frozen operation metadata and single material, with real server capabilities for addon callbacks.
 * Player, server and world references are usable only during the same authoritative server tick.
 * Database ownership, team membership and research availability remain the caller's authorization
 * duties; construction does not grant those permissions.
 */
@SuppressWarnings("unused") // Public addon context capabilities.
public final class ResearchItemDownloadContext {
    private final ResearchRegistry registry;
    private final int downloadLevel;
    private final ItemStack material;
    private final ResearchDefinition research;
    private final EntityPlayerMP actor;
    private final MinecraftServer server;
    private final WorldServer world;
    private final UUID activeDataCenterMachineId;
    private final String teamKey;
    private final long worldTick;
    private final UUID operationId;

    public ResearchItemDownloadContext(ResearchRegistry registry, int downloadLevel, ItemStack material,
                                       ResearchDefinition research, EntityPlayerMP actor, MinecraftServer server,
                                       WorldServer world, UUID activeDataCenterMachineId, String teamKey,
                                       long worldTick, UUID operationId) {
        this.registry = Objects.requireNonNull(registry, "Research registry");
        this.downloadLevel = downloadLevel;
        this.research = Objects.requireNonNull(research, "Research");
        this.actor = Objects.requireNonNull(actor, "Download actor");
        this.server = Objects.requireNonNull(server, "Download server");
        this.world = Objects.requireNonNull(world, "Download world");
        this.activeDataCenterMachineId = DownloadValidation.requireUuid(activeDataCenterMachineId, "Database UUID");
        this.teamKey = DownloadValidation.requireTeamKey(teamKey);
        this.worldTick = worldTick;
        this.operationId = DownloadValidation.requireUuid(operationId, "Download operation UUID");
        if (!registry.isFrozen()) {
            throw new IllegalArgumentException("Download context requires a frozen research registry");
        }
        if (registry.require(research.id()) != research) {
            throw new IllegalArgumentException("Download research must be the canonical registered definition");
        }
        if (downloadLevel < 1 || downloadLevel > research.maxLevel()) {
            throw new IllegalArgumentException("Download level is outside the registered research range");
        }
        validateFor(registry);
        this.material = DownloadValidation.copyMaterial(material);
    }

    public int downloadLevel() {
        return downloadLevel;
    }

    public ItemStack material() {
        return material.copy();
    }

    AEItemKey materialKey() {
        return Objects.requireNonNull(AEItemKey.of(material));
    }

    public ResearchDefinition research() {
        return research;
    }

    public EntityPlayerMP actor() {
        return actor;
    }

    public MinecraftServer server() {
        return server;
    }

    public WorldServer world() {
        return world;
    }

    public UUID activeDataCenterMachineId() {
        return activeDataCenterMachineId;
    }

    public String teamKey() {
        return teamKey;
    }

    public long worldTick() {
        return worldTick;
    }

    public UUID operationId() {
        return operationId;
    }

    void validateFor(ResearchRegistry expectedRegistry) {
        if (registry != expectedRegistry) {
            throw new IllegalArgumentException("Download context requires the evaluator's frozen research registry");
        }
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Research item download requires the authoritative server thread");
        }
        if (world.getMinecraftServer() != server
            || server.getWorld(world.provider.getDimension()) != world) {
            throw new IllegalArgumentException("Download world must be the authoritative world of this server");
        }
        if (actor instanceof FakePlayer || actor.connection == null
            || actor.getServerWorld() != world
            || server.getPlayerList().getPlayerByUUID(actor.getUniqueID()) != actor) {
            throw new IllegalArgumentException("Download actor must be a connected real player in this world");
        }
        if (worldTick < 0 || worldTick != world.getTotalWorldTime()) {
            throw new IllegalArgumentException("Download context is not from the current world tick");
        }
    }
}
