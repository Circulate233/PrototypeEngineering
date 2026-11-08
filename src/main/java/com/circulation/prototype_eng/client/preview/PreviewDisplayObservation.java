package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/** Bounded owner callback compares published representatives only; NBT remains explicitly unknown. */
public final class PreviewDisplayObservation {
    private PreviewDisplayObservation() {}
    @SuppressWarnings("deprecation")
    public static StructureProjectionState.Status observe(@Nullable World world, @Nullable StructurePreviewModel model,
                                                          StructurePreviewModel.Cell cell, BlockPos position) {
        if (world == null || model == null) return StructureProjectionState.Status.UNKNOWN;
        if (!world.isBlockLoaded(position, false)) return StructureProjectionState.Status.UNLOADED;
        var display = cell.display();
        if (display == null || display.tileNbt() != null) return StructureProjectionState.Status.UNKNOWN;
        var root = model.generation().entries().get(model.structureId());
        var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
        var actual = world.getBlockState(position);
        var chosen = model.settings().displayOptions().get(display);
        boolean unresolved = false;
        for (int index = 0; index < display.options().size(); index++) {
            if (chosen != null && chosen != index) continue;
            var option = display.options().get(index);
            if (!ForgeRegistries.BLOCKS.containsKey(option.blockId()) || !option.nbt().isEmpty()) { unresolved = true; continue; }
            var block = ForgeRegistries.BLOCKS.getValue(option.blockId());
            if (block == null) { unresolved = true; continue; }
            if (actual == PreviewBlockStates.rotate(block.getStateFromMeta(option.meta()), rotation)) return StructureProjectionState.Status.DISPLAY_MATCH;
        }
        if (unresolved) return StructureProjectionState.Status.UNKNOWN;
        return actual.getBlock().isAir(actual, world, position) ? StructureProjectionState.Status.MISSING : StructureProjectionState.Status.DIFFERENT;
    }
}
