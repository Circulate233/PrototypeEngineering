package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ModelLoader;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Keeps item and world model customization attached to the same machine declaration. */
public final class MachineControllerModels {
    private static final ResourceLocation DEFAULT_CONTROLLER = new ResourceLocation(Tags.MOD_ID, "default_controller");

    private MachineControllerModels() { }

    public static void registerModels() {
        for (var block : MachineControllerBlocks.all()) {
            var location = Objects.requireNonNullElse(block.type().controllerModel(), DEFAULT_CONTROLLER);
            ModelLoader.setCustomStateMapper(block, new StateMapperBase() {
                @Override protected @NotNull ModelResourceLocation getModelResourceLocation(@NotNull IBlockState state) {
                    return new ModelResourceLocation(location, getPropertyString(state.getProperties()));
                }
            });
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(location, "inventory"));
        }
    }
}
