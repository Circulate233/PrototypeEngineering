package com.circulation.prototype_eng.integration.jei;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.jei.JeiPatternRecipe;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/** One machine's native controller R output. Each wrapper has independent camera, choices and owned meshes. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class StructurePreviewJeiRecipe implements IRecipeWrapper, AutoCloseable {
    private final MachineType machine;
    private final List<ItemStack> controllers;
    private StructurePreviewView view;
    private StructurePreviewPanel panel;

    public StructurePreviewJeiRecipe(MachineType machine, List<ItemStack> controllers) {
        this.machine = Objects.requireNonNull(machine);
        this.controllers = controllers.stream().map(ItemStack::copy).toList();
    }

    @Override
    public void getIngredients(IIngredients ingredients) {
        if (!controllers.isEmpty())
            ingredients.setOutputs(VanillaTypes.ITEM, controllers.stream().map(ItemStack::copy).toList());
    }

    @Override
    public void drawInfo(Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
        if (view == null || view.previews().closed())
            view = new StructurePreviewView(StructurePreviews.client(), machine.structureId(), new StructurePreviewSettings(BlockOrientation.NORTH_UP), machine);
        if (panel == null || panel.closed()) panel = new StructurePreviewPanel(view);
        panel.draw(StructurePreviewJeiCategory.WIDTH, StructurePreviewJeiCategory.HEIGHT, mouseX, mouseY, minecraft.getRenderPartialTicks(), true);
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
    }

    @Nullable Pattern patternRecipe() {
        if (panel == null || panel.closed() || view == null || view.model() == null) return null;
        var scene = panel.scene();
        var materials = scene.materials();
        if (materials == null) return null;
        var output = controllers.stream().filter(stack -> !stack.isEmpty()).findFirst().orElse(ItemStack.EMPTY).copy();
        if (output.isEmpty()) return null;
        output.setCount(1);
        var key = AEItemKey.of(output);
        if (key == null) return null;
        var recipe = new JeiPatternRecipe(materials.stream().map(List::of).toList(), List.of(List.of(new GenericStack(key, 1))));
        return new Pattern(recipe, () -> panel != null && !panel.closed() && panel.scene() == scene && scene.materialsCurrent(materials));
    }

    record Pattern(JeiPatternRecipe recipe, BooleanSupplier current) { }

    @Override
    public void close() {
        retirePanel();
    }
}
