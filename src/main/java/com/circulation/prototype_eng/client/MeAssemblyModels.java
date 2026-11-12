package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.MeAssemblyBlocks;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Objects;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class MeAssemblyModels {
    private MeAssemblyModels() {}

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        for (var block : MeAssemblyBlocks.resources())
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(Objects.requireNonNull(block.getRegistryName()), "inventory"));
        var pattern = MeAssemblyBlocks.pattern();
        ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(pattern), 0,
            new ModelResourceLocation(Objects.requireNonNull(pattern.getRegistryName()), "inventory"));
    }
}
