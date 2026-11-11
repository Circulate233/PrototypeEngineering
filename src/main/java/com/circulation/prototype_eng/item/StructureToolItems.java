package com.circulation.prototype_eng.item;

import com.circulation.prototype_eng.Tags;
import net.minecraft.item.Item;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class StructureToolItems {
    public static final StructureToolItem SCANNER = new StructureToolItem(false);
    public static final StructureToolItem BUILDER = new StructureToolItem(true);

    private StructureToolItems() {
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().registerAll(SCANNER, BUILDER);
    }

    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        event.getRegistry().register(new BuilderToolFusionRecipe());
    }
}
