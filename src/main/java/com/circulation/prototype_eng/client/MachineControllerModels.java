package com.circulation.prototype_eng.client;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.item.Item;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.model.ModelLoader;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Keeps item and world model customization attached to the same machine declaration.
 */
public final class MachineControllerModels {
    private static final ResourceLocation DEFAULT_CONTROLLER = new ResourceLocation(Tags.MOD_ID, "default_controller");

    private MachineControllerModels() {
    }

    public static void registerModels() {
        for (var block : MachineControllerBlocks.all()) {
            var location = Objects.requireNonNullElse(block.type().controllerModel(), DEFAULT_CONTROLLER);
            ModelLoader.setCustomStateMapper(block, new StateMapperBase() {
                @Override
                protected @NotNull ModelResourceLocation getModelResourceLocation(@NotNull IBlockState state) {
                    return new ModelResourceLocation(location, getPropertyString(state.getProperties()));
                }
            });
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(location, "inventory"));
        }
    }

    /** PM TwistMath derives the turn from the actual front/top axes, not AE's spin numbering. */
    static int originalTwist(BlockOrientation orientation) {
        var front = orientation.getSide(RelativeSide.FRONT);
        var top = orientation.getSide(RelativeSide.TOP);
        var current = switch (front) {
            case UP -> EnumFacing.NORTH;
            case DOWN -> EnumFacing.SOUTH;
            default -> EnumFacing.UP;
        };
        for (int twist = 0; twist < 4; twist++) {
            if (current == top) return twist;
            current = EnumFacing.getFacingFromVector(
                front.getYOffset() * current.getZOffset() - front.getZOffset() * current.getYOffset(),
                front.getZOffset() * current.getXOffset() - front.getXOffset() * current.getZOffset(),
                front.getXOffset() * current.getYOffset() - front.getYOffset() * current.getXOffset());
        }
        throw new IllegalArgumentException("Invalid controller orientation: " + orientation);
    }

    public static void bakeModels(ModelBakeEvent event) {
        var visited = new ObjectOpenHashSet<ModelResourceLocation>();
        for (var block : MachineControllerBlocks.all()) {
            var location = Objects.requireNonNullElse(block.type().controllerModel(), DEFAULT_CONTROLLER);
            var mapper = new StateMapperBase() {
                @Override
                protected @NotNull ModelResourceLocation getModelResourceLocation(@NotNull IBlockState state) {
                    return new ModelResourceLocation(location, getPropertyString(state.getProperties()));
                }
            };
            for (var entry : mapper.putStateModelLocations(block).entrySet()) {
                var orientation = BlockOrientation.get(block.getOrientationStrategy(), entry.getKey());
                var facing = orientation.getSide(RelativeSide.FRONT);
                int twist = originalTwist(orientation);
                var key = entry.getValue();
                if (facing.getAxis().isVertical() || twist == 0 || !visited.add(key)) continue;
                var model = event.getModelRegistry().getObject(key);
                if (model != null && !(model instanceof ControllerRotatedModel))
                    event.getModelRegistry().putObject(key, new ControllerRotatedModel(model, facing, twist));
            }
        }
    }
}
