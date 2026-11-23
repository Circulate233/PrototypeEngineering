package com.circulation.prototype_eng.integration.jei;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.jei.JeiPatternRecipe;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.gui.recipes.RecipeLayout;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A structure's main blocks are its JEI outputs and are borrowed read-only.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
@SideOnly(Side.CLIENT)
public final class StructurePreviewJeiRecipe implements IRecipeWrapper, AutoCloseable {
    private final @Nullable MachineType machine;
    private final @Nullable IBlockState anchor;
    private final StructureConstructionRegistry.@Nullable Entry binding;
    private final List<ItemStack> mainBlocks;
    private final Int2ObjectOpenHashMap<IngredientSlot> ingredientSlots = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet visibleIngredients = new IntOpenHashSet();
    private StructurePreviewView view;
    private StructurePreviewPanel panel;
    private IRecipeLayout layout;
    private Object transferToken, transferMaterials;

    public StructurePreviewJeiRecipe(MachineType machine, List<ItemStack> controllers) {
        this(machine, null, null, controllers);
    }

    StructurePreviewJeiRecipe(IBlockState anchor, StructureConstructionRegistry.Entry binding, ItemStack output) {
        this(null, anchor, binding, output.isEmpty() ? List.of() : List.of(output));
    }

    private StructurePreviewJeiRecipe(@Nullable MachineType machine, @Nullable IBlockState anchor,
                                      StructureConstructionRegistry.@Nullable Entry binding, List<ItemStack> mainBlocks) {
        this.machine = machine;
        this.anchor = anchor;
        this.binding = binding;
        this.mainBlocks = mainBlocks;
    }

    private StructurePreviewView createView() {
        var service = StructurePreviews.client();
        if (binding != null && anchor != null)
            return new StructurePreviewView(service, new StructurePreviewSettings(binding.orientation(anchor, null)), anchor, binding);
        var type = Objects.requireNonNull(machine);
        return new StructurePreviewView(service, type.structureId(), new StructurePreviewSettings(BlockOrientation.NORTH_UP), type);
    }

    @Override
    public void getIngredients(IIngredients ingredients) {
        if (!mainBlocks.isEmpty()) ingredients.setOutputs(VanillaTypes.ITEM, mainBlocks);
    }

    @Override
    public void drawInfo(Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
        if (view == null || view.previews().closed()) view = createView();
        visibleIngredients.clear();
        if (panel == null || panel.closed()) {
            panel = new StructurePreviewPanel(view);
            if (layout != null) panel.ingredientSlots(this::showIngredient, this::clearIngredients);
        }
        panel.draw(StructurePreviewJeiCategory.WIDTH, StructurePreviewJeiCategory.HEIGHT, mouseX, mouseY, minecraft.getRenderPartialTicks(), true);
        var previous = ingredientSlots.keySet().iterator();
        while (previous.hasNext()) {
            int index = previous.nextInt();
            if (!visibleIngredients.contains(index)) {
                if (layout != null) layout.getItemStacks().getGuiIngredients().remove(index);
                previous.remove();
            }
        }
        // HEI caches its first transfer error before the async preview has been drawn.
        // Refresh that native button when either the selected generation or its ready materials change.
        var materials = panel.scene().materials();
        if ((transferToken != view.token() || transferMaterials != materials) && layout instanceof RecipeLayout nativeLayout
            && nativeLayout.getRecipeTransferButton() != null && minecraft.player != null) {
            transferToken = view.token();
            transferMaterials = materials;
            nativeLayout.getRecipeTransferButton().init(minecraft.player.openContainer, minecraft.player);
        }
    }

    private void showIngredient(int index, ItemStack stack, int x, int y) {
        var slots = layout.getItemStacks();
        visibleIngredients.add(index);
        var previous = ingredientSlots.get(index);
        boolean moved = previous == null || previous.x != x || previous.y != y || !slots.getGuiIngredients().containsKey(index);
        if (moved) slots.init(index, true, x, y);
        if (moved || !ItemStack.areItemStacksEqual(previous.stack, stack)) {
            slots.set(index, stack);
            ingredientSlots.put(index, new IngredientSlot(stack, x, y));
        }
    }

    private void clearIngredients() {
        ingredientSlots.clear();
        visibleIngredients.clear();
        if (layout != null) layout.getItemStacks().getGuiIngredients().clear();
    }

    @Override
    public List<String> getTooltipStrings(int x, int y) {
        return panel == null ? List.of() : panel.controlTooltip(x, y);
    }

    @Override
    public boolean handleClick(Minecraft minecraft, int x, int y, int button) {
        if (panel == null || panel.closed()) return false;
        boolean accepted = panel.press(x, y, button);
        if (accepted) panel.release(x, y, button);
        return accepted;
    }

    public void retirePanel() {
        if (panel != null) panel.close();
        panel = null;
        clearIngredients();
        transferToken = transferMaterials = null;
    }

    public void bindLayout(IRecipeLayout next) {
        retirePanel();
        layout = next;
        layout.getItemStacks().addTooltipCallback((index, _, _, tooltip) -> {
            if (panel != null) tooltip.addAll(panel.ingredientTooltip(index));
        });
    }

    @Nullable Pattern patternRecipe() {
        if (panel == null || panel.closed() || view == null || view.model() == null) return null;
        var scene = panel.scene();
        var materials = scene.materials();
        if (materials == null) return null;
        var output = ItemStack.EMPTY;
        for (int index = 0; index < mainBlocks.size(); index++) {
            var stack = mainBlocks.get(index);
            if (!stack.isEmpty()) {
                output = stack;
                break;
            }
        }
        if (output.isEmpty()) return null;
        var key = AEItemKey.of(output);
        if (key == null) return null;
        var inputs = new ObjectArrayList<List<GenericStack>>(materials.size());
        for (int index1 = 0; index1 < materials.size(); index1++) {
            var material = materials.get(index1);
            inputs.add(List.of(material));
        }
        var recipe = new JeiPatternRecipe(inputs, List.of(List.of(new GenericStack(key, 1))));
        return new Pattern(recipe, () -> panel != null && !panel.closed() && panel.scene() == scene && scene.materialsCurrent(materials));
    }

    @Override
    public void close() {
        retirePanel();
    }

    private record IngredientSlot(ItemStack stack, int x, int y) {
    }

    record Pattern(JeiPatternRecipe recipe, BooleanSupplier current) {
    }
}
