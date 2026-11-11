package com.circulation.prototype_eng.item;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.structure.tool.BuilderMiningTool;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.registries.IForgeRegistryEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Consumes one builder and one tool; even a tool's normal crafting container is consumed. */
public final class BuilderToolFusionRecipe extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {
    public BuilderToolFusionRecipe() {
        setRegistryName(new ResourceLocation(Tags.MOD_ID, "builder_tool_fusion"));
    }

    private static @Nullable Inputs inputs(InventoryCrafting inventory) {
        ItemStack builder = ItemStack.EMPTY, tool = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof StructureToolItem item && item.builder() && builder.isEmpty()) builder = stack;
            else if (BuilderMiningTool.accepts(stack) && tool.isEmpty()) tool = stack;
            else return null;
        }
        return builder.isEmpty() || tool.isEmpty() ? null : new Inputs(builder, tool);
    }

    @Override
    public boolean matches(@NotNull InventoryCrafting inventory, @NotNull World world) {
        return inputs(inventory) != null;
    }

    @Override
    public @NotNull ItemStack getCraftingResult(@NotNull InventoryCrafting inventory) {
        var inputs = inputs(inventory);
        return inputs == null ? ItemStack.EMPTY : BuilderMiningTool.fused(inputs.builder(), inputs.tool());
    }

    @Override
    public boolean canFit(int width, int height) {
        return (long) width * height >= 2;
    }

    @Override
    public @NotNull ItemStack getRecipeOutput() {
        return ItemStack.EMPTY;
    }

    @Override
    public @NotNull NonNullList<ItemStack> getRemainingItems(@NotNull InventoryCrafting inventory) {
        return NonNullList.withSize(inventory.getSizeInventory(), ItemStack.EMPTY);
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    private record Inputs(ItemStack builder, ItemStack tool) { }
}
