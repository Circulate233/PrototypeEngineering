package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.DecorativeBlocks;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;


@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class DecorativeBlockModels {
    private DecorativeBlockModels() {
    }

    @SubscribeEvent
    @SuppressWarnings("DataFlowIssue") // ModelRegistryEvent follows registration of the listed blocks.
    public static void registerModels(ModelRegistryEvent event) {
        for (var block : DecorativeBlocks.all()) {
            var location = block.getRegistryName();
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(location, "inventory"));
        }
    }
}
