package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.MeResourceHatchBlockEntity;
import com.circulation.prototype_eng.storage.MePatternAssemblyBlockEntity;
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
public final class MeAssemblyBlocks {
    private static final MePatternAssemblyBlock PATTERN = new MePatternAssemblyBlock();
    private static final List<MeResourceHatchBlock> RESOURCES = List.of(
        new MeResourceHatchBlock("me_input_assembly", HatchMode.INPUT),
        new MeResourceHatchBlock("me_output_assembly", HatchMode.OUTPUT),
        new MeResourceHatchBlock("me_interface", HatchMode.MIXED));

    private MeAssemblyBlocks() {}

    public static List<MeResourceHatchBlock> resources() {
        return RESOURCES;
    }

    public static MePatternAssemblyBlock pattern() {
        return PATTERN;
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        for (var block : RESOURCES) event.getRegistry().register(block);
        event.getRegistry().register(PATTERN);
        GameRegistry.registerTileEntity(MeResourceHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "me_resource_assembly"));
        GameRegistry.registerTileEntity(MePatternAssemblyBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "me_pattern_assembly"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (var block : RESOURCES)
            event.getRegistry().register(block.createItem().setRegistryName(Objects.requireNonNull(block.getRegistryName())));
        event.getRegistry().register(PATTERN.createItem().setRegistryName(Objects.requireNonNull(PATTERN.getRegistryName())));
    }
}
