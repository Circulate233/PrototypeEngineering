package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.UUID;

/**
 * Frozen operation metadata and single material, with real server capabilities for addon callbacks.
 * Player, server and world references are usable only during the same authoritative server tick.
 * Database ownership, team membership and research availability remain the caller's authorization
 * duties; construction does not grant those permissions. Use the evaluator's research registry,
 * a valid level and a connected real player in the supplied world at worldTick. Material is one item,
 * borrowed read-only for the operation lifetime.
 */
@SuppressWarnings("unused") // Public addon context capabilities.
public final class ResearchItemDownloadContext {
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
        this.downloadLevel = downloadLevel;
        this.research = research;
        this.actor = actor;
        this.server = server;
        this.world = world;
        this.activeDataCenterMachineId = activeDataCenterMachineId;
        this.teamKey = teamKey;
        this.worldTick = worldTick;
        this.operationId = operationId;
        this.material = material;
    }

    public int downloadLevel() {
        return downloadLevel;
    }

    public ItemStack material() {
        return material;
    }

    AEItemKey materialKey() {
        return AEItemKey.of(material);
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

}
