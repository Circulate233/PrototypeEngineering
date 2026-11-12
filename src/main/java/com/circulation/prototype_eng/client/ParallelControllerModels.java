package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.ParallelControllerBlocks;
import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.ModelBakeEvent;
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

    @SubscribeEvent
    public static void bakeModels(ModelBakeEvent event) {
        var visited = new ObjectOpenHashSet<ModelResourceLocation>();
        for (var block : ParallelControllerBlocks.all()) {
            var location = block.type().modelLocation();
            var mapper = new StateMapperBase() {
                @Override
                protected @NotNull ModelResourceLocation getModelResourceLocation(@NotNull IBlockState state) {
                    return new ModelResourceLocation(location, getPropertyString(state.getProperties()));
                }
            };
            for (var entry : mapper.putStateModelLocations(block).entrySet()) {
                var orientation = BlockOrientation.get(block.getOrientationStrategy(), entry.getKey());
                var facing = orientation.getSide(RelativeSide.FRONT);
                int twist = MachineControllerModels.originalTwist(orientation);
                var key = entry.getValue();
                if (facing.getAxis().isVertical() || twist == 0 || !visited.add(key)) continue;
                var model = event.getModelRegistry().getObject(key);
                if (model != null && !(model instanceof ControllerRotatedModel))
                    event.getModelRegistry().putObject(key, new ControllerRotatedModel(model, facing, twist));
            }
        }
    }
}
