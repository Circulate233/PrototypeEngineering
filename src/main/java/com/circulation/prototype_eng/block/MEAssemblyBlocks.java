package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.MEPatternAssemblyBlockEntity;
import com.circulation.prototype_eng.storage.MEResourceHatchBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.List;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class MEAssemblyBlocks {
    private static final MEPatternAssemblyBlock PATTERN = new MEPatternAssemblyBlock();
    private static final List<MEResourceHatchBlock> RESOURCES = List.of(
        new MEResourceHatchBlock("me_input_assembly", HatchMode.INPUT),
        new MEResourceHatchBlock("me_output_assembly", HatchMode.OUTPUT),
        new MEResourceHatchBlock("me_interface", HatchMode.MIXED));

    private MEAssemblyBlocks() {
    }

    public static List<MEResourceHatchBlock> resources() {
        return RESOURCES;
    }

    public static MEPatternAssemblyBlock pattern() {
        return PATTERN;
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        for (int index = 0; index < RESOURCES.size(); index++) {
            var block = RESOURCES.get(index);
            event.getRegistry().register(block);
        }
        event.getRegistry().register(PATTERN);
        GameRegistry.registerTileEntity(MEResourceHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "me_resource_assembly"));
        GameRegistry.registerTileEntity(MEPatternAssemblyBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "me_pattern_assembly"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (int index2 = 0; index2 < RESOURCES.size(); index2++) {
            var block = RESOURCES.get(index2);
            event.getRegistry().register(block.createItem().setRegistryName(Objects.requireNonNull(block.getRegistryName())));
        }
        event.getRegistry().register(PATTERN.createItem().setRegistryName(Objects.requireNonNull(PATTERN.getRegistryName())));
    }
}
