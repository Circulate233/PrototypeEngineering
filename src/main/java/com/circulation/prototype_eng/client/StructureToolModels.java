package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.item.StructureToolItems;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class StructureToolModels {
    private StructureToolModels() {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(StructureToolItems.SCANNER, 0, new ModelResourceLocation(Tags.MOD_ID + ":scanner_instrument", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StructureToolItems.BUILDER, 0, new ModelResourceLocation(Tags.MOD_ID + ":build_instrument", "inventory"));
    }
}
