package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.MEAssemblyBlocks;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;


@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class MEAssemblyModels {
    private MEAssemblyModels() {
    }

    @SubscribeEvent
    @SuppressWarnings("DataFlowIssue") // ModelRegistryEvent follows registration of the listed blocks.
    public static void registerModels(ModelRegistryEvent event) {

        var elements = MEAssemblyBlocks.resources();
        for (int index = 0; index < elements.size(); index++) {
            var block = elements.get(index);
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(block.getRegistryName(), "inventory"));
        }

        var pattern = MEAssemblyBlocks.pattern();
        ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(pattern), 0,
            new ModelResourceLocation(pattern.getRegistryName(), "inventory"));
    }
}
