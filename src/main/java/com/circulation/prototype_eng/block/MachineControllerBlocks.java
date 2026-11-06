package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.List;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class MachineControllerBlocks {
    private static List<MachineControllerBlock> blocks = List.of();
    private MachineControllerBlocks() { }
    public static List<MachineControllerBlock> all() { return blocks; }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        var registry = MachineTypeRegistry.INSTANCE;
        MinecraftForge.EVENT_BUS.post(new MachineTypeRegistrationEvent(registry));
        registry.freeze();
        var generated = new ObjectArrayList<MachineControllerBlock>();
        for (var type : registry.types()) { generated.add(new MachineControllerBlock(type)); }
        // Even an empty library needs its generic tile mapping for downstream loading and saves.
        GameRegistry.registerTileEntity(MachineControllerBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "machine_controller"));
        event.getRegistry().registerAll(generated.toArray(MachineControllerBlock[]::new));
        blocks = ObjectLists.unmodifiable(generated);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (var block : blocks) {
            event.getRegistry().register(new MachineControllerItem(block).setRegistryName(Objects.requireNonNull(block.getRegistryName())));
        }
    }
}
