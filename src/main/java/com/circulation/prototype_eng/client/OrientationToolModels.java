package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.item.ControllerOrientationTool;
import com.circulation.prototype_eng.item.OrientationToolItems;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/** Client predicates and model registration never initialize from the server item constructor. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class OrientationToolModels {
    private OrientationToolModels() { }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        var item = OrientationToolItems.TOOL;
        item.addPropertyOverride(new ResourceLocation("enabled"),
            (stack, _, _) -> ControllerOrientationTool.isEnabled(stack) ? 1.0F : 0.0F);
        ModelLoader.setCustomModelResourceLocation(item, 0,
            new ModelResourceLocation(Tags.MOD_ID + ":orientation_tool", "inventory"));
    }
}
