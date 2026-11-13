package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.storage.ComputationHatchBlockEntity;
import com.circulation.prototype_eng.storage.ResourceHatchBlockEntity;
import com.google.common.collect.ImmutableList;
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
public final class HatchBlocks {
    private static final List<HatchBlock<?>> BLOCKS = createBlocks();

    private HatchBlocks() {
    }

    private static List<HatchBlock<?>> createBlocks() {
        var blocks = ImmutableList.<HatchBlock<?>>builderWithExpectedSize(64);
        for (var tier : HatchTier.VALUES) {
            for (var mode : HatchMode.VALUES) {
                blocks.add(new ResourceHatchBlock(mode, tier));
                blocks.add(new EnergyHatchBlock(mode, tier));
            }
        }
        for (int tier = 1; tier <= 4; tier++) blocks.add(new ComputationHatchBlock(tier));
        return blocks.build();
    }

    public static List<HatchBlock<?>> all() {
        return BLOCKS;
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        var size = BLOCKS.size();
        for (var i = 0; i < size; i++) {
            event.getRegistry().register(BLOCKS.get(i));
        }
        GameRegistry.registerTileEntity(ResourceHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "resource_hatch"));
        GameRegistry.registerTileEntity(EnergyHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "energy_hatch"));
        GameRegistry.registerTileEntity(ComputationHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "computation_hatch"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        var size = BLOCKS.size();
        for (var i = 0; i < size; i++) {
            var block = BLOCKS.get(i);
            event.getRegistry().register(block.createItem().setRegistryName(Objects.requireNonNull(block.getRegistryName())));
        }
    }
}
