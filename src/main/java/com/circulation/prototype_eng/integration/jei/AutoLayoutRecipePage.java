package com.circulation.prototype_eng.integration.jei;

import ae2.api.client.AEKeyRendering;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Objects;
import javax.annotation.ParametersAreNonnullByDefault;

import mcp.MethodsReturnNonnullByDefault;
import com.circulation.prototype_eng.localization.StructurePreviewText;

/**
 * Pool-wide maximum slots and a bounded, explicitly paged default display, following MMCE.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AutoLayoutRecipePage implements RecipePoolJeiPage {
    private static final int WIDTH = 174;
    private static final int HEIGHT = 148;
    private static final int INPUT_X = 2;
    private static final int OUTPUT_X = 100;
    private static final int RESOURCE_Y = 18;
    private static final int STRIDE = 18;
    private static final int INFORMATION_Y = 94;
    private static final int FOOTER_Y = 135;
    private final RecipePool pool;
    private final IDrawable background;
    private final int inputSlots;
    private final int outputSlots;

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays displays) {
        this.pool = Objects.requireNonNull(pool);
        int inputMaximum = 0;
        int outputMaximum = 0;
        for (var recipe : pool.recipes()) {
            var layout = new RecipePanelLayout(displays.describe(recipe));
            inputMaximum = Math.max(inputMaximum, layout.inputSlots());
            outputMaximum = Math.max(outputMaximum, layout.outputSlots());
        }
        inputSlots = inputMaximum;
        outputSlots = outputMaximum;
        background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
    }

    @Override
    public RecipePool pool() {
        return pool;
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
        return new AutoLayoutRecipe(this, recipe, display, ingredients);
    }

    @Override
    public void setRecipe(IRecipeLayout layout, RecipePoolJeiRecipe wrapper, IIngredients ingredients) {
        if (!(wrapper instanceof AutoLayoutRecipe recipe) || recipe.page() != this) {
            throw new IllegalArgumentException("Default JEI page received a foreign wrapper");
        }
        recipe.layout = layout;
        recipe.ingredients().initialize(layout, inputSlots, outputSlots, RecipePanelLayout.COLUMNS,
            INPUT_X, OUTPUT_X, RESOURCE_Y, STRIDE);
        recipe.refresh();
        layout.setRecipeId(pool.id().getNamespace(), recipe.recipe().id());
    }

    @Override
    public void drawExtras(Minecraft minecraft) {
        minecraft.fontRenderer.drawString(JeiText.Input.getLocal(), INPUT_X, 2, 0x404040);
        minecraft.fontRenderer.drawString(JeiText.Output.getLocal(), OUTPUT_X, 2, 0x404040);
        var machines = JeiText.Machines.getLocal(pool.machines().size());
        minecraft.fontRenderer.drawString(minecraft.fontRenderer.trimStringToWidth(machines, 54), 42, 2, 0x404040);
        frames(inputSlots, INPUT_X);
        frames(outputSlots, OUTPUT_X);
        Gui.drawRect(80, 42, 95, 47, 0xFF777777);
        Gui.drawRect(91, 38, 95, 51, 0xFF777777);
    }

    private static void frames(int count, int x) {
        for (int i = 0; i < count; i++) {
            int px = x + i % RecipePanelLayout.COLUMNS * STRIDE;
            int py = RESOURCE_Y + i / RecipePanelLayout.COLUMNS * STRIDE;
            Gui.drawRect(px - 1, py - 1, px + 17, py + 17, 0xFF8B8B8B);
            Gui.drawRect(px, py, px + 16, py + 16, 0xFFF0F0F0);
        }
    }

    /**
     * Per-wrapper state keeps panel changes from mutating another recipe or pool's category.
     */
    public static class AutoLayoutRecipe extends RecipePoolJeiRecipe {
        private final AutoLayoutRecipePage owner;
        private final RecipePanelLayout panels;
        private int panel;
        private IRecipeLayout layout;
        private boolean choosingMachine;
        private int machineOffset;

        public AutoLayoutRecipe(AutoLayoutRecipePage owner, MachineRecipe recipe, JeiRecipeDisplay display,
                                JeiRecipeIngredients ingredients) {
            super(owner, recipe, display, ingredients);
            this.owner = owner;
            panels = new RecipePanelLayout(display);
        }

        private void refresh() {
            if (layout != null) {
                ingredients().show(layout, panels.inputs(panel), panels.outputs(panel), owner.inputSlots, owner.outputSlots);
            }
        }

        @Override
        public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
            long period = Math.max(1L, recipe().durationTicks()) * 50L;
            int progress = (int) (System.currentTimeMillis() % period * 15 / period);
            Gui.drawRect(80, 43, 80 + progress, 46, 0xFF62AE3E);
            resources(minecraft, panels.inputs(panel), INPUT_X);
            resources(minecraft, panels.outputs(panel), OUTPUT_X);
            var lines = panels.information(panel);
            for (int i = 0; i < lines.size(); i++) {
                var full = lines.get(i).local();
                var shown = minecraft.fontRenderer.getStringWidth(full) <= WIDTH - 4 ? full
                    : minecraft.fontRenderer.trimStringToWidth(full, WIDTH - 14) + JeiText.More.getLocal();
                minecraft.fontRenderer.drawString(shown, 2, INFORMATION_Y + i * 9, 0x404040);
            }
            if (panels.pageCount() > 1) {
                Gui.drawRect(0, FOOTER_Y - 1, 22, HEIGHT, 0xFFBBBBBB);
                Gui.drawRect(WIDTH - 22, FOOTER_Y - 1, WIDTH, HEIGHT, 0xFFBBBBBB);
                minecraft.fontRenderer.drawString("<", 8, FOOTER_Y, 0x404040);
                minecraft.fontRenderer.drawString(">", WIDTH - 14, FOOTER_Y, 0x404040);
                var pageText = JeiText.Page.getLocal(panel + 1, panels.pageCount());
                minecraft.fontRenderer.drawString(pageText, (WIDTH - minecraft.fontRenderer.getStringWidth(pageText)) / 2,
                    FOOTER_Y, 0x404040);
            }
            if (choosingMachine) {
                Gui.drawRect(0, 0, WIDTH, HEIGHT, 0xee30343c);
                minecraft.fontRenderer.drawString(JeiText.Machines.getLocal(owner.pool.machines().size()), 4, 4, 0xffffff);
                int visible = 7;
                machineOffset = Math.clamp(machineOffset, 0, Math.max(0, owner.pool.machines().size() - visible));
                for (int row = 0; row < visible && machineOffset + row < owner.pool.machines().size(); row++) {
                    var machine = owner.pool.machines().get(machineOffset + row);
                    int y = 20 + row * 16;
                    if (mouseX >= 2 && mouseX < WIDTH - 2 && mouseY >= y && mouseY < y + 15) Gui.drawRect(2, y, WIDTH - 2, y + 15, 0xff4080aa);
                    minecraft.fontRenderer.drawString(minecraft.fontRenderer.trimStringToWidth(machine.displayName().getLocal(), WIDTH - 8), 4, y + 4, 0xffffff);
                }
            }
        }

        private void resources(Minecraft minecraft, List<JeiRecipeDisplay.Resource> resources, int x) {
            for (int i = 0; i < resources.size(); i++) {
                var row = resources.get(i);
                int px = x + i % RecipePanelLayout.COLUMNS * STRIDE;
                int py = RESOURCE_Y + i / RecipePanelLayout.COLUMNS * STRIDE;
                if (!ingredients().supports(row)) {
                    if (AEKeyRendering.get(row.stack().what().getType()) != null) {
                        AEKeyRendering.drawInGui(minecraft, px, py, row.stack().what());
                    } else {
                        minecraft.fontRenderer.drawString("?", px + 4, py + 4, 0xAA2222);
                    }
                    JeiRecipeIngredients.renderAmount(minecraft, px, py, row);
                }
            }
        }

        @Override
        public List<String> getTooltipStrings(int mouseX, int mouseY) {
            if (choosingMachine) {
                int row = (mouseY - 20) / 16;
                if (mouseY >= 20 && row < 7 && machineOffset + row < owner.pool.machines().size()) {
                    var machine = owner.pool.machines().get(machineOffset + row);
                    return ObjectArrayList.of(machine.displayName().getLocal(), machine.id().toString(), StructurePreviewText.Open.getLocal());
                }
                return ObjectArrayList.of();
            }
            if (mouseX >= 42 && mouseX < 98 && mouseY >= 0 && mouseY < 14) {
                var names = new ObjectArrayList<String>();
                names.add(JeiText.Machines.getLocal(owner.pool.machines().size()));
                for (var machine : owner.pool.machines()) {
                    names.add(machine.displayName().getLocal() + " (" + machine.id() + ")");
                }
                return names;
            }
            if (mouseX >= 78 && mouseX < 98 && mouseY >= 36 && mouseY < 54) {
                return ObjectArrayList.of(JeiText.Duration.getLocal(recipe().durationTicks(), recipe().durationTicks() / 20.0),
                    JeiText.Runtime.getLocal());
            }
            var fallback = resourceTooltip(panels.inputs(panel), INPUT_X, mouseX, mouseY);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            fallback = resourceTooltip(panels.outputs(panel), OUTPUT_X, mouseX, mouseY);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            int line = (mouseY - INFORMATION_Y) / 9;
            var lines = panels.information(panel);
            if (mouseX >= 0 && mouseX < WIDTH && mouseY >= INFORMATION_Y && line < lines.size()) {
                return ObjectArrayList.of(lines.get(line).local());
            }
            if (panels.pageCount() > 1 && mouseY >= FOOTER_Y - 1 && mouseY < HEIGHT) {
                return ObjectArrayList.of((mouseX < WIDTH / 2 ? JeiText.Previous : JeiText.Next).getLocal());
            }
            return ObjectArrayList.of();
        }

        private List<String> resourceTooltip(List<JeiRecipeDisplay.Resource> resources, int x, int mouseX, int mouseY) {
            for (int i = 0; i < resources.size(); i++) {
                var row = resources.get(i);
                int px = x + i % RecipePanelLayout.COLUMNS * STRIDE;
                int py = RESOURCE_Y + i / RecipePanelLayout.COLUMNS * STRIDE;
                if (!ingredients().supports(row) && mouseX >= px && mouseX < px + 16 && mouseY >= py && mouseY < py + 16) {
                    var tooltip = new ObjectArrayList<>(JeiRecipeIngredients.tooltip(row));
                    tooltip.add(JeiText.UnsupportedIngredient.getLocal());
                    if (ingredients().queryConflict(row)) {
                        tooltip.add(JeiText.QueryIdentityConflict.getLocal());
                    }
                    if (AEKeyRendering.get(row.stack().what().getType()) == null) {
                        tooltip.add(JeiText.MissingRenderer.getLocal());
                    }
                    return tooltip;
                }
            }
            return ObjectArrayList.of();
        }

        @Override
        public boolean handleClick(Minecraft minecraft, int mouseX, int mouseY, int mouseButton) {
            if (mouseButton == 0 && choosingMachine) {
                int row = (mouseY - 20) / 16;
                if (mouseX >= 0 && mouseX < WIDTH && mouseY >= 20 && row < 7 && machineOffset + row < owner.pool.machines().size()) {
                    var machine = owner.pool.machines().get(machineOffset + row);
                    choosingMachine = false;
                    StructurePreviewJeiScreens.open(machine);
                    return true;
                }
                choosingMachine = false;
                return true;
            }
            if (mouseButton == 0 && mouseX >= 42 && mouseX < 98 && mouseY >= 0 && mouseY < 14 && !owner.pool.machines().isEmpty()) {
                choosingMachine = true;
                return true;
            }
            if (mouseButton != 0 || panels.pageCount() <= 1 || mouseY < FOOTER_Y - 1 || mouseY >= HEIGHT) {
                return false;
            }
            if (mouseX >= 0 && mouseX < 22) {
                panel = Math.floorMod(panel - 1, panels.pageCount());
            } else if (mouseX >= WIDTH - 22 && mouseX < WIDTH) {
                panel = (panel + 1) % panels.pageCount();
            } else {
                return false;
            }
            refresh();
            return true;
        }

        @Override
        public boolean handleMouseScroll(int mouseX, int mouseY, int scrollDelta) {
            if (choosingMachine && scrollDelta != 0 && mouseX >= 0 && mouseX < WIDTH && mouseY >= 0 && mouseY < HEIGHT) {
                machineOffset = Math.clamp(machineOffset + (scrollDelta > 0 ? -1 : 1), 0, Math.max(0, owner.pool.machines().size() - 7));
                return true;
            }
            if (panels.pageCount() <= 1 || scrollDelta == 0 || mouseX < 0 || mouseX >= WIDTH || mouseY < 0 || mouseY >= HEIGHT) {
                return false;
            }
            panel = Math.floorMod(panel + (scrollDelta > 0 ? -1 : 1), panels.pageCount());
            refresh();
            return true;
        }
    }
}
