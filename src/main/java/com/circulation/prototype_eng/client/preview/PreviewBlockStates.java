package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
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
        for (var option : display.options())
            if (ForgeRegistries.BLOCKS.containsKey(option.blockId())
                && ForgeRegistries.BLOCKS.getValue(option.blockId()) != null) return true;
        return false;
    }

    @SuppressWarnings("deprecation") // Published 1.12 display options carry canonical registry metadata.
    static IBlockState first(StructurePreviewModel model, StructurePreviewModel.Cell cell) {
        var explicit = model.settings().displayOptions().get(cell.display());
        if (explicit != null) return selected(model, cell, explicit);
        var display = cell.display();
        if (display == null) return null;
        var root = model.generation().entries().get(model.structureId());
        var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
        for (var option : display.options()) {
            // containsKey prevents Forge's missing registry lookup from silently returning AIR.
            if (!ForgeRegistries.BLOCKS.containsKey(option.blockId())) continue;
            var block = ForgeRegistries.BLOCKS.getValue(option.blockId());
            if (block != null) return rotate(block.getStateFromMeta(option.meta()), rotation);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    static IBlockState selected(StructurePreviewModel model, StructurePreviewModel.Cell cell, int optionIndex) {
        var display = cell.display();
        if (display == null || optionIndex < 0 || optionIndex >= display.options().size()) return null;
        var option = display.options().get(optionIndex);
        if (!ForgeRegistries.BLOCKS.containsKey(option.blockId())) return null;
        var block = ForgeRegistries.BLOCKS.getValue(option.blockId());
        if (block == null) return null;
        var root = model.generation().entries().get(model.structureId());
        var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
        return rotate(block.getStateFromMeta(option.meta()), rotation);
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
