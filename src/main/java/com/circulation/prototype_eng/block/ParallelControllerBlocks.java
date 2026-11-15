package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.ParallelControllerConfig;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.ParallelControllerRegistrationEvent;
import com.circulation.prototype_eng.api.machine.ParallelControllerRegistry;
import com.circulation.prototype_eng.api.machine.ParallelControllerType;
import com.circulation.prototype_eng.localization.ParallelControllerText;
import com.circulation.prototype_eng.parallel.ParallelControllerBlockEntity;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.List;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ParallelControllerBlocks {
    private static List<ParallelControllerBlock> blocks = List.of();

    private ParallelControllerBlocks() {
    }

    public static List<ParallelControllerBlock> all() {
        return blocks;
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        var registry = ParallelControllerRegistry.INSTANCE;
        registerBuiltins(registry);
        MinecraftForge.EVENT_BUS.post(new ParallelControllerRegistrationEvent());
        var created = new ObjectArrayList<ParallelControllerBlock>();

        var elements = registry.freeze();
        for (int index = 0; index < elements.size(); index++) {
            var type = elements.get(index);
            created.add(new ParallelControllerBlock(type));
        }

        event.getRegistry().registerAll(created.toArray(ParallelControllerBlock[]::new));
        GameRegistry.registerTileEntity(ParallelControllerBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "parallel_controller"));
        blocks = created;
    }

    private static void registerBuiltins(ParallelControllerRegistry registry) {
        ParallelControllerText[] names = {ParallelControllerText.Normal, ParallelControllerText.Reinforced, ParallelControllerText.Elite,
            ParallelControllerText.Super, ParallelControllerText.Ultimate};
        for (int tier = 0; tier < names.length; tier++) {
            String name = "parallel_controller_" + ParallelControllerConfig.TIERS[tier];
            registry.register(new ParallelControllerType(new ResourceLocation(Tags.MOD_ID, name), names[tier],
                ParallelControllerConfig.maximum(tier), new ResourceLocation(Tags.MOD_ID, name)));
        }
    }

    @SubscribeEvent
    @SuppressWarnings("DataFlowIssue")
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (int index2 = 0; index2 < blocks.size(); index2++) {
            var block = blocks.get(index2);
            event.getRegistry().register(block.createItem().setRegistryName(block.getRegistryName()));
        }
    }
}
