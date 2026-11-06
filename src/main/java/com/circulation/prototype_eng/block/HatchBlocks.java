package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.storage.ResourceHatchBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class HatchBlocks {
    public static final HatchBlock INPUT = new HatchBlock("input_hatch", () -> new ResourceHatchBlockEntity(HatchMode.INPUT));
    public static final HatchBlock OUTPUT = new HatchBlock("output_hatch", () -> new ResourceHatchBlockEntity(HatchMode.OUTPUT));
    public static final HatchBlock MIXED = new HatchBlock("mixed_hatch", () -> new ResourceHatchBlockEntity(HatchMode.MIXED));
    public static final HatchBlock ENERGY = new HatchBlock("energy_hatch", EnergyHatchBlockEntity::new);

    private HatchBlocks() {}

    public static HatchBlock[] all() { return new HatchBlock[]{INPUT, OUTPUT, MIXED, ENERGY}; }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().registerAll(all());
        GameRegistry.registerTileEntity(ResourceHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "resource_hatch"));
        GameRegistry.registerTileEntity(EnergyHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "energy_hatch"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (HatchBlock block : all()) {
            event.getRegistry().register(new ItemBlock(block).setRegistryName(block.getRegistryName()));
        }
    }
}
