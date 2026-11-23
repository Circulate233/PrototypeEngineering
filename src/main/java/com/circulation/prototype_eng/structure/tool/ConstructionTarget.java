package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public record ConstructionTarget(World world, BlockPos position, ResourceLocation binding, ResourceLocation structure,
                                 LocalizationEnum name, BlockOrientation orientation,
                                 @Nullable MachineControllerHost controller, IBlockState anchorState) {
    public static @Nullable ConstructionTarget resolve(World world, BlockPos position) {
        if (!world.isBlockLoaded(position, false)) return null;
        var state = world.getBlockState(position);
        if (world.getTileEntity(position) instanceof MachineControllerHost host)
            return new ConstructionTarget(world, position.toImmutable(), host.type().id(), host.type().structureId(),
                host.type().displayName(), host.orientation(), host, state);
        var entry = StructureConstructionRegistry.INSTANCE.find(state);
        if (entry == null) return null;
        return new ConstructionTarget(world, position.toImmutable(), state.getBlock().getRegistryName(), entry.structure(),
            entry.name(), entry.orientation().apply(state), null, state);
    }

    boolean formedForConstruction() {
        if (world.isRemote) throw new IllegalStateException("Construction formation is server-side");
        if (controller != null) return controller.formed();
        var entry = StructureConstructionRegistry.INSTANCE.find(anchorState);
        return entry != null && entry.formation() != null
            && entry.isFormed(anchorState, world.getTileEntity(position), world, position);
    }

    public void atBoundary(Runnable action) {
        if (controller != null) controller.readGuiAtBoundary(action);
        else action.run();
    }

    public boolean current() {
        if (!world.isBlockLoaded(position, false)) return false;
        if (controller != null)
            return world.getTileEntity(position) == controller && !controller.blockEntity().isInvalid()
                && controller.type().id().equals(binding) && controller.orientation() == orientation;
        var state = world.getBlockState(position);
        if (anchorState.equals(state)) return true;
        if (state.getBlock() != anchorState.getBlock()) return false;
        var entry = StructureConstructionRegistry.INSTANCE.find(state);
        return entry != null && entry.structure().equals(structure) && entry.orientation().apply(state) == orientation;
    }
}
