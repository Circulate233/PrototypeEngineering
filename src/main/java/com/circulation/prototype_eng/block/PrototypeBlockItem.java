package com.circulation.prototype_eng.block;

import ae2.block.AEBaseBlockItem;
import mezz.jei.api.ingredients.ISlowRenderItem;
import net.minecraft.block.Block;
import net.minecraftforge.fml.common.Optional;

/**
 * Uses native item rendering so CTM light data and mixed diffuse/emissive faces survive JEI batching.
 */
@Optional.Interface(iface = "mezz.jei.api.ingredients.ISlowRenderItem", modid = "jei")
public class PrototypeBlockItem extends AEBaseBlockItem implements ISlowRenderItem {
    public PrototypeBlockItem(Block block) {
        super(block);
    }
}
