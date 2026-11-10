package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Item-side disk contents; custom items may expose special research without ordinary cloud encoding.
 */
public interface ResearchDiskCarrier {
    /**
     * Physical disk tier supplied by the item's own registration or per-stack layout.
     */
    int researchDiskTier(ItemStack stack);

    /**
     * Cheap interaction probe; custom formats override this before the block's normal GUI activation.
     */
    default boolean hasResearchDiskData(ItemStack stack) {
        var tag = stack.getTagCompound();
        return tag != null && tag.hasKey(ResearchDiskNbtCodec.PAYLOAD_TAG);
    }

    /**
     * Blank or unsupported stacks return null. Malformed encoded data must be rejected, never partially parsed.
     */
    default @Nullable ResearchDiskData readResearchDisk(ItemStack stack, ResearchRegistry registry) {
        return hasResearchDiskData(stack) ? ResearchDiskNbtCodec.read(stack, registry) : null;
    }
}
