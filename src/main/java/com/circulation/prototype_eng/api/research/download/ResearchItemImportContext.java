package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.UUID;

/**
 * Owner-thread item import context. Material is one item borrowed read-only; evaluating a recipe
 * cannot mutate the player, database, world or research. The caller owns all subsequent effects.
 */
public record ResearchItemImportContext(ResearchRegistry registry, ItemStack material, EntityPlayerMP actor,
                                        UUID dataCenterId, String teamId, UUID operationId) {
}
