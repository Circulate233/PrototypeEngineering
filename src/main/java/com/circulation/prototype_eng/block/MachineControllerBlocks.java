package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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
    private static List<MachineControllerBlock<?>> blocks = List.of();

    private MachineControllerBlocks() {
    }

    public static List<MachineControllerBlock<?>> all() {
        return blocks;
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        var registry = MachineTypeRegistry.INSTANCE;
        var declarations = new MachineTypeRegistrationEvent(registry);
        MinecraftForge.EVENT_BUS.post(declarations);
        registry.freeze();
        var factories = declarations.controllerFactories();
        for (var type : factories.keySet()) {
            if (registry.get(type) == null) {
                throw new IllegalArgumentException("Controller factory has no machine declaration: " + type);
            }
        }
        var generated = new ObjectArrayList<MachineControllerBlock<?>>(registry.types().size());
        var names = new ObjectOpenHashSet<ResourceLocation>(registry.types().size());
        for (var type : registry.types()) {
            var factory = factories.get(type.id());
            var block = factory == null ? new BasicMachineControllerBlock(type)
                : Objects.requireNonNull(factory.apply(type), "Controller factory returned null");
            if (block.type() != type || block.getRegistryName() == null) {
                throw new IllegalArgumentException("Controller factory must bind its canonical machine type and registry name");
            }
            if (!names.add(block.getRegistryName())) {
                throw new IllegalArgumentException("Duplicate controller block registry name: " + block.getRegistryName());
            }
            generated.add(block);
        }
        // Even an empty library needs its generic tile mapping for downstream loading and saves.
        GameRegistry.registerTileEntity(MachineControllerBlockEntity.class, new ResourceLocation(Tags.MOD_ID, "machine_controller"));
        event.getRegistry().registerAll(generated.toArray(MachineControllerBlock<?>[]::new));
        blocks = ObjectLists.unmodifiable(generated);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        var generated = new ObjectArrayList<Item>(blocks.size());
        for (var block : blocks) {
            var item = Objects.requireNonNull(block.createItem(), "Controller item factory returned null");
            if (item.getBlock() != block) {
                throw new IllegalArgumentException("Controller item factory must target its own block");
            }
            generated.add(item.setRegistryName(Objects.requireNonNull(block.getRegistryName())));
        }
        event.getRegistry().registerAll(generated.toArray(Item[]::new));
    }
}
