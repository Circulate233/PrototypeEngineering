package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One material conversion and its fixed research requirement and level payment. A null research id
 * accepts the research selected by the caller. Templates, keys and callbacks are retained read-only
 * for the registry lifetime; callback outputs are transferred read-only to the transaction.
 */
public record ResearchItemDownloadRecipe(ResourceLocation id, AEItemKey material,
                                         @Nullable ResourceLocation researchId, int researchLevel,
                                         int levelCost, ItemStack outputTemplate,
                                         ResearchItemDownloadEvent event) {
    public ResearchItemDownloadRecipe {
        if (researchLevel < 1 || levelCost < 0 || outputTemplate.isEmpty()) {
            throw new IllegalArgumentException("Invalid research download requirement, payment or output");
        }
    }
}
