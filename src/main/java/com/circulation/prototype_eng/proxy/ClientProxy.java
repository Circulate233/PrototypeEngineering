package com.circulation.prototype_eng.proxy;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.HatchBlocks;
import com.circulation.prototype_eng.client.gui.EnergyHatchGui;
import com.circulation.prototype_eng.client.gui.ResourceHatchGui;
import com.circulation.prototype_eng.container.EnergyHatchContainer;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public class ClientProxy extends CommonProxy {
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return switch (getServerGuiElement(id, player, world, x, y, z)) {
            case ResourceHatchContainer resource -> new ResourceHatchGui(resource, player.inventory);
            case EnergyHatchContainer energy -> new EnergyHatchGui(energy, player.inventory);
            case null, default -> null;
        };
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        for (var block : HatchBlocks.all()) {
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                    new ModelResourceLocation(block.getRegistryName(), "inventory"));
        }
    }
}
