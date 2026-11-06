package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
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

import java.util.Locale;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class HatchBlocks {
    private static final HatchBlock[] BLOCKS = createBlocks();

    private static HatchBlock[] createBlocks() {
        var blocks = new HatchBlock[60];
        int index = 0;
        for (var tier : HatchTier.values()) {
            for (var mode : HatchMode.values()) {
                String direction = mode.name().toLowerCase(Locale.ROOT);
                blocks[index++] = new HatchBlock(direction + "_hatch_" + tier.level(),
                        () -> new ResourceHatchBlockEntity(mode, tier), 5.0F);
                blocks[index++] = new HatchBlock("energy_" + direction + "_hatch_" + tier.level(),
                        () -> new EnergyHatchBlockEntity(mode, tier), 3.5F);
            }
        }
        return blocks;
    }

    private HatchBlocks() {
    }

    public static HatchBlock[] all() {
        return BLOCKS.clone();
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().registerAll(all());
        GameRegistry.registerTileEntity(ResourceHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "resource_hatch"));
        GameRegistry.registerTileEntity(EnergyHatchBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "energy_hatch"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (HatchBlock block : all()) {
            event.getRegistry().register(new ItemBlock(block).setRegistryName(Objects.requireNonNull(block.getRegistryName())));
        }
    }
}
