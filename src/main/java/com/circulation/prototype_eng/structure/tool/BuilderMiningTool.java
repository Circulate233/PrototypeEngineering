package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.item.StructureToolItem;
import ae2.api.util.IAEWrench;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** One consumed tool supplies only the dismantling task; the item never advertises a tool profile. */
public final class BuilderMiningTool {
    private static final String TOOL = "BuilderMiningTool";

    private BuilderMiningTool() { }

    public static boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && !(stack.getItem() instanceof StructureToolItem)
            && (!stack.getItem().getToolClasses(stack).isEmpty()
                || stack.getItem() instanceof IAEWrench
                || stack.getItem().isDamageable() && !(stack.getItem() instanceof ItemArmor));
    }

    public static ItemStack fused(ItemStack builder, ItemStack tool) {
        if (!(builder.getItem() instanceof StructureToolItem item) || !item.builder() || !accepts(tool))
            return ItemStack.EMPTY;
        var result = builder.copy();
        result.setCount(1);
        var profile = tool.copy();
        profile.setCount(1);
        var tag = BuilderState.data(result);
        tag.setTag(TOOL, profile.serializeNBT());
        BuilderState.state(tag, BuilderState.State.IDLE);
        BuilderState.progress(tag, 0, 0);
        return result;
    }

    public static ItemStack profile(ItemStack builder) {
        var tag = builder.getTagCompound();
        if (tag == null || !tag.hasKey(TOOL, 10)) return ItemStack.EMPTY;
        var profile = new ItemStack(tag.getCompoundTag(TOOL));
        // Never recurse through another builder, including manually edited NBT.
        return profile.getItem() instanceof StructureToolItem ? ItemStack.EMPTY : profile;
    }

    public static boolean canHarvest(ItemStack builder, IBlockState state, @Nullable EntityPlayer player) {
        if (state.getMaterial().isToolNotRequired()) return true;
        var tool = profile(builder);
        if (tool.isEmpty()) return state.getBlock().getHarvestLevel(state) <= 3;
        var toolClass = state.getBlock().getHarvestTool(state);
        if (toolClass != null) {
            int level = tool.getItem().getHarvestLevel(tool, toolClass, player, state);
            if (level >= 0) return level >= state.getBlock().getHarvestLevel(state);
        }
        return tool.getItem().canHarvestBlock(state, tool);
    }
}
