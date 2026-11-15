package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Published display alternatives only. A missing model representation never invokes a matcher/factory.
 */
final class PreviewBlockStates {
    private PreviewBlockStates() {
    }

    static boolean represented(StructurePreviewModel.Cell cell) {
        var display = cell.display();
        if (display == null) return false;
        var elements = display.options();
        for (int index = 0; index < elements.size(); index++) {
            var option = elements.get(index);
            if (ForgeRegistries.BLOCKS.containsKey(option.blockId())) return true;
        }
        return false;
    }

    static IBlockState first(StructurePreviewModel model, StructurePreviewModel.Cell cell) {
        var display = cell.display();
        if (display == null) return null;
        var explicit = model.settings().explicitOption(cell);
        if (explicit >= 0) return selected(model, display, explicit);
        var root = model.generation().entries().get(model.structureId());
        var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
        var elements2 = display.options();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var option = elements2.get(index2);
            var state = resolve(option, rotation);
            if (state != null) return state;
        }
        return null;
    }

    static IBlockState selected(StructurePreviewModel model, StructureDisplay display, int optionIndex) {
        var option = display.options().get(optionIndex);
        var root = model.generation().entries().get(model.structureId());
        var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
        return resolve(option, rotation);
    }

    @SuppressWarnings({"deprecation", "DataFlowIssue"})
    // The preceding registry membership lookup requires a registered block.
    static IBlockState resolve(StructureDisplay.BlockOption option, StructureTransform rotation) {
        // Missing registry lookups can return AIR; resolve this boundary once per option.
        if (!ForgeRegistries.BLOCKS.containsKey(option.blockId())) return null;
        var block = ForgeRegistries.BLOCKS.getValue(option.blockId());
        var state = option.resolveProperties(block.getStateFromMeta(option.meta()));
        return state == null ? null : rotate(state, rotation);
    }

    @SuppressWarnings("unchecked")
    static IBlockState rotate(IBlockState state, StructureTransform rotation) {
        var result = state;
        for (var property : state.getPropertyKeys()) {
            if (property.getValueClass() == EnumFacing.class) {
                var facing = (IProperty<EnumFacing>) property;
                var transformed = rotation.apply(state.getValue(facing));
                if (facing.getAllowedValues().contains(transformed)) result = result.withProperty(facing, transformed);
            } else if (property.getValueClass() == EnumFacing.Axis.class) {
                var axis = (IProperty<EnumFacing.Axis>) property;
                var representative = switch (state.getValue(axis)) {
                    case X -> EnumFacing.EAST;
                    case Y -> EnumFacing.UP;
                    case Z -> EnumFacing.NORTH;
                };
                var transformed = rotation.apply(representative).getAxis();
                if (axis.getAllowedValues().contains(transformed)) result = result.withProperty(axis, transformed);
            }
        }
        return result;
    }
}
