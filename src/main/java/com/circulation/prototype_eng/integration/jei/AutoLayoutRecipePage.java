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
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDecorator;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.JeiSlot;
import com.circulation.prototype_eng.api.client.jei.JeiVisuals;
import it.unimi.dsi.fastutil.objects.ObjectLists;

/**
 * Pool-wide maximum slots and a bounded, explicitly paged default display, following MMCE.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AutoLayoutRecipePage implements RecipePoolJeiPage {
    private static final int WIDTH = 174;
    private static final int HEIGHT = 160;
    private static final int INPUT_X = 2;
    private static final int OUTPUT_X = 100;
    private static final int RESOURCE_Y = 18;
    private static final int STRIDE = 18;
    private static final int INFORMATION_Y = 94;
    private static final int FOOTER_Y = 147;
    private final RecipePool pool;
    private final IDrawable background;
    private final int inputSlots;
    private final int outputSlots;
    private final JeiRenderOptions options;
    private final List<JeiSlot> inputLayout;
    private final List<JeiSlot> outputLayout;
    private final List<JeiRecipeDecorator.Placement> decorations;

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays displays) {
        this(pool, guiHelper, displays, JeiRenderOptions.defaults());
        }

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays displays, JeiRenderOptions options) {
        this.pool = Objects.requireNonNull(pool);
        Objects.requireNonNull(guiHelper);
        this.options = Objects.requireNonNull(options);
        int inputMaximum = 0;
        int outputMaximum = 0;
        for (var recipe : pool.recipes()) {
            var display = displays.describe(recipe);
            inputMaximum = Math.max(inputMaximum, RecipePanelLayout.maximumSlots(display, JeiRecipeDisplay.Role.INPUT, options));
            outputMaximum = Math.max(outputMaximum, RecipePanelLayout.maximumSlots(display, JeiRecipeDisplay.Role.OUTPUT, options));
        }
        inputSlots = inputMaximum;
        outputSlots = outputMaximum;
        background = new JeiVisuals.NineSlice(new JeiVisuals.Texture(JeiVisuals.texture("jei_base"), 184, 220), WIDTH, HEIGHT, 2, false, 0xffcdcdd4);
        inputLayout = slots(inputSlots, INPUT_X);
        outputLayout = slots(outputSlots, OUTPUT_X);
        decorations = ObjectLists.unmodifiable(ObjectArrayList.of(
            new JeiRecipeDecorator.Placement(78, 36, JeiVisuals.Progress.module(JeiVisuals.Module.RIGHT, JeiVisuals.Direction.RIGHT)),
            new JeiRecipeDecorator.Placement(2, 133, new JeiVisuals.Duration(null, WIDTH - 4, 9, JeiVisuals.Alignment.CENTER, .75f, 0xff404040, false))));
    }

    private static List<JeiSlot> slots(int count, int x) {
        var result = new ObjectArrayList<JeiSlot>();
        for (int i = 0; i < count; i++) result.add(new JeiSlot(x - 1 + i % RecipePanelLayout.COLUMNS * STRIDE,
            RESOURCE_Y - 1 + i / RecipePanelLayout.COLUMNS * STRIDE, 18, 18, 1, JeiVisuals.AUTOMATIC));
        return ObjectLists.unmodifiable(result);
    }

    /** Subclasses may supply their own native bounds/skin compositions; independent pages use JeiSlot directly. */
    public List<JeiSlot> inputLayout() { return inputLayout; }
    public List<JeiSlot> outputLayout() { return outputLayout; }
    public List<JeiRecipeDecorator.Placement> decorations() { return decorations; }
    public JeiRenderOptions renderOptions() { return options; }

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
        recipe.ingredients().initialize(layout, inputLayout(), outputLayout(), renderOptions());
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
            panels = new RecipePanelLayout(display, owner.renderOptions(), ingredients);
        }

        private void refresh() {
            if (layout != null) {
                ingredients().showGroups(layout, panels.inputGroups(panel), panels.outputGroups(panel), owner.inputLayout().size(), owner.outputLayout().size());
            }
        }

        @Override
        public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
            super.drawInfo(minecraft, recipeWidth, recipeHeight, mouseX, mouseY);
            for (var decoration : owner.decorations()) decoration.draw(minecraft, this);
            resources(minecraft, panels.inputGroups(panel), owner.inputLayout());
            resources(minecraft, panels.outputGroups(panel), owner.outputLayout());
            var lines = panels.information(panel);
            for (int i = 0; i < lines.size(); i++) {
                var full = lines.get(i).local();
                var energy = lines.get(i).energy();
                int textX = energy == null ? 2 : 13;
                if (energy != null) JeiVisuals.Energy.ONE_1.draw(minecraft, 2, INFORMATION_Y + i * 9, 9, 9,
                    energy.role(), owner.renderOptions().energyLedYOffset());
                var shown = minecraft.fontRenderer.getStringWidth(full) <= WIDTH - textX - 2 ? full
                    : minecraft.fontRenderer.trimStringToWidth(full, WIDTH - textX - 12) + JeiText.More.getLocal();
                minecraft.fontRenderer.drawString(shown, textX, INFORMATION_Y + i * 9, 0x404040);
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

        private void resources(Minecraft minecraft, List<List<JeiRecipeDisplay.Resource>> resources, List<JeiSlot> slots) {
            for (int i = 0; i < resources.size(); i++) {
                var row = resources.get(i).getFirst();
                if (!ingredients().supports(row)) {
                    JeiRecipeIngredients.renderFallback(minecraft, slots.get(i), row, owner.renderOptions());
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
            var fallback = resourceTooltip(panels.inputGroups(panel), owner.inputLayout(), mouseX, mouseY);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            fallback = resourceTooltip(panels.outputGroups(panel), owner.outputLayout(), mouseX, mouseY);
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

        private List<String> resourceTooltip(List<List<JeiRecipeDisplay.Resource>> resources, List<JeiSlot> slots, int mouseX, int mouseY) {
            for (int i = 0; i < resources.size(); i++) {
                var row = resources.get(i).getFirst();
                var slot = slots.get(i);
                if (!ingredients().supports(row) && mouseX >= slot.x() && mouseX < slot.x() + slot.width()
                    && mouseY >= slot.y() && mouseY < slot.y() + slot.height()) {
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
