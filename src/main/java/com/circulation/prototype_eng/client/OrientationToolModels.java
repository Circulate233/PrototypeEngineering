package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.item.ControllerOrientationTool;
import com.circulation.prototype_eng.item.OrientationToolItems;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ModelLoader;

/**
 * Client predicates and model registration never initialize from the server item constructor.
 */
public final class OrientationToolModels {
    private OrientationToolModels() {
    }

    public static void registerModels() {
        var item = OrientationToolItems.TOOL;
        item.addPropertyOverride(new ResourceLocation("enabled"),
            (stack, _, _) -> ControllerOrientationTool.isEnabled(stack) ? 1.0F : 0.0F);
        ModelLoader.setCustomModelResourceLocation(item, 0,
            new ModelResourceLocation(Tags.MOD_ID + ":orientation_tool", "inventory"));
    }
}
