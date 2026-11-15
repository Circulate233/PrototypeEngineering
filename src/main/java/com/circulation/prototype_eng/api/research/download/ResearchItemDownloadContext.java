package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.UUID;

/**
 * Same-tick server capabilities and the matched recipe's fixed research level. The player is
 * connected in the supplied world; material is one item borrowed read-only. Construction grants
 * no database permission. Callbacks must not mutate inventories, sources, the database or world.
 */
@SuppressWarnings("unused") // Public addon context capabilities.
public record ResearchItemDownloadContext(int downloadLevel, ItemStack material, ResearchDefinition research,
                                          EntityPlayerMP actor, MinecraftServer server, WorldServer world,
                                          UUID activeDataCenterMachineId, String teamKey, long worldTick,
                                          UUID operationId) {
    AEItemKey materialKey() {
        return AEItemKey.of(material);
    }

}
