package com.circulation.prototype_eng.item;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.OrientationToolConfig;
import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class OrientationToolItems {
    public static final ControllerOrientationTool TOOL = new ControllerOrientationTool();

    private OrientationToolItems() { }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        TOOL.setMaxDamage(OrientationToolConfig.settings().maxDurability());
        event.getRegistry().register(TOOL);
    }
}
