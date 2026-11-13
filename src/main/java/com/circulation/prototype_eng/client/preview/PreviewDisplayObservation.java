package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Bounded owner callback compares the selected target block state, without granting formation authority.
 */
public final class PreviewDisplayObservation {
    private PreviewDisplayObservation() {
    }

    public static StructureProjectionState.Status observe(@Nullable World world, @Nullable StructurePreviewModel model,
                                                          StructurePreviewModel.Cell cell, BlockPos position) {
        if (world == null || model == null) return StructureProjectionState.Status.UNKNOWN;
        if (!world.isBlockLoaded(position, false)) return StructureProjectionState.Status.UNLOADED;
        var display = cell.display();
        if (display == null || !display.complete())
            return StructureProjectionState.Status.UNKNOWN;
        var root = model.generation().entries().get(model.structureId());
        var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
        var actual = world.getBlockState(position);
        var option = display.options().get(model.settings().option(cell));
        var state = PreviewBlockStates.resolve(option, rotation);
        if (state == null) return StructureProjectionState.Status.UNKNOWN;
        if (actual.equals(state)) return StructureProjectionState.Status.DISPLAY_MATCH;
        return actual.getBlock().isAir(actual, world, position) ? StructureProjectionState.Status.MISSING : StructureProjectionState.Status.DIFFERENT;
    }
}
