package com.circulation.prototype_eng.proxy;

import ae2.container.AEBaseContainer;
import com.circulation.prototype_eng.api.client.gui.MachineScreenRegistry;
import com.circulation.prototype_eng.block.HatchBlocks;
import com.circulation.prototype_eng.client.gui.EnergyHatchGui;
import com.circulation.prototype_eng.client.gui.ResourceHatchGui;
import com.circulation.prototype_eng.client.gui.ResearchGui;
import com.circulation.prototype_eng.container.ResearchContainer;
import com.circulation.prototype_eng.client.MachineControllerModels;
import com.circulation.prototype_eng.client.OrientationToolModels;
import com.circulation.prototype_eng.container.EnergyHatchContainer;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Objects;

import com.circulation.prototype_eng.api.machine.MachineControllerHost;

public class ClientProxy extends CommonProxy {
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerModels(ModelRegistryEvent event) {
        for (var block : HatchBlocks.all()) {
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(Objects.requireNonNull(block.getRegistryName()), "inventory"));
        }
        MachineControllerModels.registerModels();
        OrientationToolModels.registerModels();
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return switch (getServerGuiElement(id, player, world, x, y, z)) {
            case ResearchContainer research -> new ResearchGui(research, player.inventory);
            case ResourceHatchContainer resource -> new ResourceHatchGui(resource, player.inventory);
            case EnergyHatchContainer energy -> new EnergyHatchGui(energy, player.inventory);
            case AEBaseContainer container when container.getTarget() instanceof MachineControllerHost machine ->
                MachineScreenRegistry.create(machine.type().id(), container, player.inventory);
            case null, default -> null;
        };
    }
}
