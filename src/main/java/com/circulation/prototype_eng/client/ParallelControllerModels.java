package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.ParallelControllerBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.jetbrains.annotations.NotNull;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class ParallelControllerModels {
    private ParallelControllerModels() {}

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        for (var block : ParallelControllerBlocks.all()) {
            var model = block.type().modelLocation();
            ModelLoader.setCustomStateMapper(block, new StateMapperBase() {
                @Override
                protected @NotNull ModelResourceLocation getModelResourceLocation(@NotNull IBlockState state) {
                    return new ModelResourceLocation(model, getPropertyString(state.getProperties()));
                }
            });
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0, new ModelResourceLocation(model, "inventory"));
        }
    }
}
