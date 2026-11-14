package com.circulation.prototype_eng.item;

import ae2.api.upgrades.Upgrades;
import ae2.core.definitions.AEItems;
import com.circulation.prototype_eng.Tags;
import net.minecraft.item.Item;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class StructureToolItems {
    public static final StructureToolItem SCANNER = new StructureToolItem();
    public static final BuilderInstrumentItem BUILDER = new BuilderInstrumentItem();

    private StructureToolItems() {
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().registerAll(SCANNER, BUILDER);
        Upgrades.add(AEItems.ENERGY_CARD.item(), BUILDER, BuilderInstrumentItem.UPGRADE_SLOTS);
        Upgrades.add(AEItems.QUANTUM_BRIDGE_CARD.item(), BUILDER, 1);
    }

    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        event.getRegistry().register(new BuilderToolFusionRecipe());
    }
}
