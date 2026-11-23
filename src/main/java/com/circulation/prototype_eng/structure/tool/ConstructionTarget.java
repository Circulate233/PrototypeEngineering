package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record ConstructionTarget(World world, BlockPos position, ResourceLocation binding, ResourceLocation structure,
                                 LocalizationEnum name, BlockOrientation orientation, @Nullable MachineControllerHost controller,
                                 IBlockState anchorState, StructureConstructionRegistry.@Nullable Entry external) {
    public static @Nullable ConstructionTarget resolve(World world, BlockPos position) {
        if (!world.isBlockLoaded(position, false)) return null;
        var state = world.getBlockState(position);
        var tile = world.getTileEntity(position);
        if (tile instanceof MachineControllerHost host)
            return new ConstructionTarget(world, position.toImmutable(), host.type().id(), host.type().structureId(),
                host.type().displayName(), host.orientation(), host, state, null);
        var entry = StructureConstructionRegistry.INSTANCE.find(state);
        if (entry == null) return null;
        return new ConstructionTarget(world, position.toImmutable(), state.getBlock().getRegistryName(), entry.structure(),
            entry.name(), entry.orientation(state, tile), null, state, entry);
    }

    public BlockOrientation orientation(StructureCatalogView catalog) {
        if (external == null || !external.fixed()) return orientation;
        var root = Objects.requireNonNull(catalog.entries().get(structure), "Unknown construction structure: " + structure);
        return catalog.nodes().get(root.node()).orientation();
    }

    boolean formedForConstruction() {
        if (world.isRemote) throw new IllegalStateException("Construction formation is server-side");
        if (controller != null) return controller.formed();
        return Objects.requireNonNull(external).isFormed(anchorState, world.getTileEntity(position), world, position);
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
        if (state.getBlock() != anchorState.getBlock()) return false;
        var entry = state.equals(anchorState) ? external : StructureConstructionRegistry.INSTANCE.find(state);
        if (entry == null || !entry.structure().equals(structure) || entry.fixed() != Objects.requireNonNull(external).fixed()) return false;
        return entry.fixed() || entry.orientation(state, world.getTileEntity(position)) == orientation;
    }
}
