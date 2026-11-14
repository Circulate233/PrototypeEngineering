package com.circulation.prototype_eng.integration.jei;

import ae2.api.client.AEKeyRendering;
import com.circulation.prototype_eng.api.client.jei.JeiEnergyIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiPatternRecipe;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDecorator;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.JeiSlot;
import com.circulation.prototype_eng.api.client.jei.JeiVisuals;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Material pages with complete, font-measured explanations, following MMCE.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AutoLayoutRecipePage implements RecipePoolJeiPage {
    private static final int WIDTH = 174;
    private static final int STRIDE = 18;
    private static final JeiVisuals.Texture EMPTY_SLOT = new JeiVisuals.Texture(JeiVisuals.texture("item_module/plaid_base"), 18, 18);
    private final RecipeInformationLayout.Font font;
    private final JeiRenderOptions options;
    private final boolean singleInput;
    private final boolean showArrow;
    private Data data;

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays displays) {
        this(pool, guiHelper, displays, JeiRenderOptions.defaults());
    }

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays displays, JeiRenderOptions options) {
        this(pool, guiHelper, displays, options, minecraftFont());
    }

    /**
     * Supplies the font metrics used to measure and wrap every recipe in this category.
     */
    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays displays, JeiRenderOptions options,
                                RecipeInformationLayout.Font font) {
        this(pool, guiHelper, displays::describe, options, font);
    }

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays.Snapshot displays) {
        this(pool, guiHelper, displays::describe, JeiRenderOptions.defaults(), minecraftFont());
    }

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays.Snapshot displays, JeiRenderOptions options) {
        this(pool, guiHelper, displays::describe, options, minecraftFont());
    }

    public AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, RecipeJeiDisplays.Snapshot displays, JeiRenderOptions options,
                                RecipeInformationLayout.Font font) {
        this(pool, guiHelper, displays::describe, options, font);
    }

    private AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, Function<MachineRecipe, JeiRecipeDisplay> describe,
                                 JeiRenderOptions options, RecipeInformationLayout.Font font) {
        this(pool, guiHelper, describe, options, font, false);
    }

    AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, Function<MachineRecipe, JeiRecipeDisplay> describe,
                         JeiRenderOptions options, RecipeInformationLayout.Font font, boolean singleInput) {
        this(pool, guiHelper, describe, options, font, singleInput, true);
    }

    AutoLayoutRecipePage(RecipePool pool, IGuiHelper guiHelper, Function<MachineRecipe, JeiRecipeDisplay> describe,
                         JeiRenderOptions options, RecipeInformationLayout.Font font, boolean singleInput, boolean showArrow) {
        this.font = Objects.requireNonNull(font);
        Objects.requireNonNull(pool);
        Objects.requireNonNull(guiHelper);
        this.options = Objects.requireNonNull(options);
        this.singleInput = singleInput;
        this.showArrow = showArrow;
        data = measure(pool, describe);
    }

    static RecipeInformationLayout.Font minecraftFont() {
        var renderer = Minecraft.getMinecraft().fontRenderer;
        return new RecipeInformationLayout.Font(renderer.FONT_HEIGHT,
            (text, width) -> renderer.listFormattedStringToWidth(text.local(), width));
    }

    private static List<JeiSlot> slots(int count, int columns, int x, int y) {
        var result = new ObjectArrayList<JeiSlot>();
        for (int i = 0; i < count; i++)
            result.add(new JeiSlot(x + i % columns * STRIDE, y + i / columns * STRIDE, 18, 18, 1, JeiVisuals.AUTOMATIC));
        return ObjectLists.unmodifiable(result);
    }

    private static int columns(int count, int limit) {
        return count == 0 ? 0 : Math.clamp((int) Math.ceil(Math.sqrt(count)), (count + 3) / 4, limit);
    }

    private static int rows(int count, int columns) {
        return count == 0 ? 0 : (count + columns - 1) / columns;
    }

    private int inputCapacity() {
        return singleInput ? 1 : RecipePanelLayout.RESOURCE_CAPACITY;
    }

    private int outputCapacity() {
        return singleInput ? 24 : RecipePanelLayout.RESOURCE_CAPACITY;
    }

    private Data measure(RecipePool pool, Function<MachineRecipe, JeiRecipeDisplay> describe) {
        var information = new Reference2ObjectOpenHashMap<JeiRecipeDisplay, RecipeInformationLayout>();
        int contentBottom = 21;
        int maximumInputs = 0;
        int maximumOutputs = 0;
        boolean paginated = false;
        for (var recipe : pool.recipes()) {
            var display = describe.apply(recipe);
            var measured = RecipeInformationLayout.measure(display.information(), WIDTH, font);
            information.put(display, measured);
            int inputs = RecipePanelLayout.maximumSlots(display, JeiRecipeDisplay.Role.INPUT, options, Integer.MAX_VALUE);
            int outputs = RecipePanelLayout.maximumSlots(display, JeiRecipeDisplay.Role.OUTPUT, options, Integer.MAX_VALUE);
            maximumInputs = Math.clamp(inputs, maximumInputs, inputCapacity());
            maximumOutputs = Math.clamp(outputs, maximumOutputs, outputCapacity());
            var geometry = geometry(Math.min(inputs, inputCapacity()), Math.min(outputs, outputCapacity()));
            contentBottom = Math.max(contentBottom, geometry.informationY + measured.height());
            paginated |= inputs > inputCapacity() || outputs > outputCapacity();
        }
        int height = contentBottom + (paginated ? 26 : 6);
        var background = new JeiVisuals.NineSlice(new JeiVisuals.Texture(JeiVisuals.texture("jei_base"), 184, 220), WIDTH, height, 2, false, 0xffffffff);
        return new Data(pool, height, height - 20, background, information, geometry(maximumInputs, maximumOutputs));
    }

    @Override
    public @Nullable PreparedUpdate prepareUpdate(RecipePool target, RecipeJeiDisplays.Snapshot displays) {
        // An existing subclass may customize geometry/wrappers and must explicitly supply this contract.
        if (getClass() != AutoLayoutRecipePage.class) return null;
        return prepareLayoutUpdate(target, displays);
    }

    protected final PreparedUpdate prepareLayoutUpdate(RecipePool target, RecipeJeiDisplays.Snapshot displays) {
        var expected = data;
        var prepared = measure(target, displays::describe);
        return new Update(this, expected, prepared);
    }

    private Geometry geometry(int inputCount, int outputCount) {
        int inputColumns = columns(inputCount, singleInput ? 1 : 4);
        int outputColumns = columns(outputCount, singleInput ? 6 : 4);
        int inputHeight = rows(inputCount, inputColumns) * STRIDE;
        int outputHeight = rows(outputCount, outputColumns) * STRIDE;
        int height = Math.max(showArrow ? STRIDE : 0, Math.max(inputHeight, outputHeight));
        int gap = showArrow ? 24 : 0;
        int width = (inputColumns + outputColumns) * STRIDE + gap;
        int x = (WIDTH - width) / 2;
        int arrowX = x + inputColumns * STRIDE + 3;
        return new Geometry(slots(inputCount, inputColumns, x, 3 + (height - inputHeight) / 2),
            slots(outputCount, outputColumns, x + inputColumns * STRIDE + gap, 3 + (height - outputHeight) / 2),
            showArrow ? List.of(new JeiRecipeDecorator.Placement(arrowX, 3 + (height - STRIDE) / 2,
                JeiVisuals.Progress.module(JeiVisuals.Module.RIGHT, JeiVisuals.Direction.RIGHT))) : List.of(), 3 + height + 6);
    }

    /**
     * Pool-wide maximum geometry; each wrapper uses only the slots on its current material page.
     */
    public List<JeiSlot> inputLayout() {
        return data.maximum.inputs;
    }

    public List<JeiSlot> outputLayout() {
        return data.maximum.outputs;
    }

    public JeiRenderOptions renderOptions() {
        return options;
    }

    @Override
    public RecipePool pool() {
        return data.pool;
    }

    @Override
    public IDrawable getBackground() {
        return data.background;
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
        if (!recipe.isCurrent()) return;
        recipe.layout = layout;
        recipe.refresh(true);
        layout.setRecipeId(recipe.pool().id().getNamespace(), recipe.recipe().id());
    }

    @Override
    public void drawExtras(Minecraft minecraft) {
        // Slot backgrounds belong to the wrapper's actual page, not the category's maximum grid.
    }

    private record Update(AutoLayoutRecipePage owner, Data expected, Data prepared) implements PreparedUpdate {
        @Override
        public RecipePoolJeiPage page() {
            return owner;
        }

        @Override
        public RecipePool pool() {
            return prepared.pool;
        }

        @Override
        public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            return new AutoLayoutRecipe(owner, prepared, recipe, display, ingredients);
        }

        @Override
        public void apply() {
            if (owner.data != expected)
                throw new IllegalStateException("JEI page preparation is obsolete or already applied");
            owner.data = prepared;
        }
    }

    private record Data(RecipePool pool, int height, int footerTop, IDrawable background,
                        Reference2ObjectOpenHashMap<JeiRecipeDisplay, RecipeInformationLayout> information,
                        Geometry maximum) {
    }

    private record Geometry(List<JeiSlot> inputs, List<JeiSlot> outputs,
                            List<JeiRecipeDecorator.Placement> decorations, int informationY) {
    }

    /**
     * Per-wrapper state keeps panel changes from mutating another recipe or pool's category.
     */
    public static class AutoLayoutRecipe extends RecipePoolJeiRecipe {
        private final AutoLayoutRecipePage owner;
        private final Data captured;
        private final RecipePanelLayout panels;
        private final RecipeInformationLayout explanation;
        private final List<Geometry> geometries;
        private Geometry geometry;
        private int panel;
        private IRecipeLayout layout;

        public AutoLayoutRecipe(AutoLayoutRecipePage owner, MachineRecipe recipe, JeiRecipeDisplay display,
                                JeiRecipeIngredients ingredients) {
            this(owner, owner.data, recipe, display, ingredients);
        }

        private AutoLayoutRecipe(AutoLayoutRecipePage owner, Data captured, MachineRecipe recipe, JeiRecipeDisplay display,
                                 JeiRecipeIngredients ingredients) {
            super(owner, captured.pool, recipe, display, ingredients);
            this.owner = owner;
            this.captured = captured;
            panels = new RecipePanelLayout(display, owner.renderOptions(), ingredients, owner.inputCapacity(), owner.outputCapacity(), owner.singleInput);
            geometries = new ObjectArrayList<>(panels.pageCount());
            for (int i = 0; i < panels.pageCount(); i++)
                geometries.add(owner.geometry(panels.inputGroups(i).size(), panels.outputGroups(i).size()));
            geometry = geometries.getFirst();
            explanation = Objects.requireNonNull(captured.information.get(display), "Display was not measured for this category");
        }

        private void refresh(boolean initialize) {
            var previous = geometry;
            geometry = geometries.get(panel);
            if (layout != null) {
                if (initialize)
                    ingredients().initialize(layout, geometry.inputs, geometry.outputs, owner.renderOptions());
                else
                    ingredients().reposition(layout, previous.inputs.size() + previous.outputs.size(), geometry.inputs, geometry.outputs, owner.renderOptions());
                ingredients().showGroups(layout, panels.inputGroups(panel), panels.outputGroups(panel),
                    geometry.inputs.size(), geometry.outputs.size());
                int energySlot = 0;
                for (var paragraph : explanation.paragraphs())
                    if (paragraph.source().energy() != null)
                        JeiEnergyIngredients.slot(layout, energySlot++, paragraph.source().energy(), 4, geometry.informationY + paragraph.y(), 18, 18);
            }
        }

        @Override
        public JeiPatternRecipe patternRecipe() {
            return panels.patternRecipe(recipe().durationTicks());
        }

        @Override
        public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
            if (!isCurrent()) return;
            super.drawInfo(minecraft, recipeWidth, recipeHeight, mouseX, mouseY);
            for (var slot : geometry.inputs)
                EMPTY_SLOT.draw(minecraft, slot.x(), slot.y(), slot.width(), slot.height());
            for (var slot : geometry.outputs)
                EMPTY_SLOT.draw(minecraft, slot.x(), slot.y(), slot.width(), slot.height());
            for (var decoration : geometry.decorations) decoration.draw(minecraft, this);
            resources(minecraft, panels.inputGroups(panel), geometry.inputs);
            resources(minecraft, panels.outputGroups(panel), geometry.outputs);
            for (var paragraph : explanation.paragraphs()) {
                int y = geometry.informationY + paragraph.y();
                var energy = paragraph.source().energy();
                if (energy != null) JeiVisuals.Energy.ONE_1.draw(minecraft, 4, y, 18, 18,
                    energy.role(), owner.renderOptions().energyLedYOffset());
                int textY = y + (paragraph.height() - paragraph.lines().size() * owner.font.lineHeight()) / 2;
                for (var line : paragraph.lines()) {
                    minecraft.fontRenderer.drawString(line, paragraph.x(), textY, 0x404040);
                    textY += owner.font.lineHeight();
                }
            }
            if (panels.pageCount() > 1) {
                JeiVisuals.Progress.module(JeiVisuals.Module.LEFT, JeiVisuals.Direction.LEFT).drawProgress(minecraft, 2, captured.footerTop,
                    mouseX >= 0 && mouseX < 22 && mouseY >= captured.footerTop && mouseY < captured.height ? 1 : 0);
                JeiVisuals.Progress.module(JeiVisuals.Module.RIGHT, JeiVisuals.Direction.RIGHT).drawProgress(minecraft, WIDTH - 20, captured.footerTop,
                    mouseX >= WIDTH - 22 && mouseX < WIDTH && mouseY >= captured.footerTop && mouseY < captured.height ? 1 : 0);
                var pageText = JeiText.Page.getLocal(panel + 1, panels.pageCount());
                minecraft.fontRenderer.drawString(pageText, (WIDTH - minecraft.fontRenderer.getStringWidth(pageText)) / 2,
                    captured.footerTop + 5, 0x404040);
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
            if (!isCurrent()) return ObjectArrayList.of();
            var fallback = resourceTooltip(panels.inputGroups(panel), geometry.inputs, mouseX, mouseY);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            fallback = resourceTooltip(panels.outputGroups(panel), geometry.outputs, mouseX, mouseY);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            if (panels.pageCount() > 1 && mouseX >= 0 && mouseX < WIDTH && mouseY >= captured.footerTop && mouseY < captured.height) {
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
            if (!isCurrent() || mouseButton != 0 || panels.pageCount() <= 1 || mouseY < captured.footerTop || mouseY >= captured.height) {
                return false;
            }
            if (mouseX >= 0 && mouseX < 22) {
                panel = Math.floorMod(panel - 1, panels.pageCount());
            } else if (mouseX >= WIDTH - 22 && mouseX < WIDTH) {
                panel = (panel + 1) % panels.pageCount();
            } else {
                return false;
            }
            refresh(false);
            return true;
        }

        @Override
        public boolean handleMouseScroll(int mouseX, int mouseY, int scrollDelta) {
            if (!isCurrent() || panels.pageCount() <= 1 || scrollDelta == 0 || mouseX < 0 || mouseX >= WIDTH || mouseY < 0 || mouseY >= captured.height) {
                return false;
            }
            panel = Math.floorMod(panel + (scrollDelta > 0 ? -1 : 1), panels.pageCount());
            refresh(false);
            return true;
        }
    }
}
