package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Single-input import declaration shared by server evaluation and client recipe display.
 * Templates and callbacks are retained read-only for the registry lifetime.
 */
public record ResearchItemImportRecipe(ResourceLocation id, AEItemKey material,
                                       ResourceLocation researchId, int researchLevel,
                                       ItemStack outputTemplate, ResearchItemImportEvent event) {
}
