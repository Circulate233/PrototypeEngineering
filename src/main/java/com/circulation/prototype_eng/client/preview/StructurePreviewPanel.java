package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.client.gui.GuiAtlas;
import com.circulation.prototype_eng.client.gui.GuiClip;
import com.circulation.prototype_eng.client.gui.GuiLabel;
import com.circulation.prototype_eng.localization.PreviewUiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.Slot;
import ae2.util.inv.AppEngInternalInventory;
import ae2.container.slot.AppEngSlot;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** PM's implemented 184x220 preview UI, shared unchanged by the standalone and JEI hosts. */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class StructurePreviewPanel implements AutoCloseable {
    public static final int WIDTH = 184, HEIGHT = 220;
    static final int SCENE_X = 6, SCENE_Y = 13;
    private static final int MATERIAL_COLUMNS = 5, MATERIAL_ROWS = 8, MATERIAL_STEP = 19;
    private static final Rect MATERIAL_PANEL = new Rect(29, 31, 134, 163);
    private static final Rect MATERIAL_CONTENT = new Rect(32, 34, 107, 157);
    private static final Rect MATERIAL_SCROLL = new Rect(152, 34, 10, 157);
    private final StructurePreviewView view;
    private final StructurePreviewScene scene;
    private final EntityPlayer player;
    private final Object world, connection;
    private final Container container;
    private final int window;
    private final GuiClip clip = new GuiClip();
    private final ObjectArrayList<Action> actions = new ObjectArrayList<>();
    private final ObjectArrayList<ItemSlot> itemSlots = new ObjectArrayList<>();
    private final AppEngInternalInventory displayInventory = new AppEngInternalInventory(null, 64, Integer.MAX_VALUE);
    private final ObjectArrayList<Slot> displaySlots = new ObjectArrayList<>();
    private SlotRenderer ingredientSlots;
    private Runnable clearIngredients = () -> { };
    private final PreviewListScroll alternativesScroll = new PreviewListScroll(), materialsScroll = new PreviewListScroll();
    private int alternativesCount, materialsCount;
    private boolean construction;
    private ItemStack builderTool = ItemStack.EMPTY;
    private Supplier<NBTTagCompound> builderBinding = NBTTagCompound::new;
    private Runnable materialsRequested = () -> { };
    private BiConsumer<StructurePreviewModel.Cell, Integer> cellAlternativeSelected = (_, _) -> { };
    private StructureDisplay constructionChoice;
    private BlockPos constructionCell;
    private StructurePreviewModel.Cell lastConstructionPick;
    private Object constructionMaterialsToken;
    private List<ConstructionMaterial> constructionMaterials = List.of();
    private BiConsumer<StructureDisplay, Integer> alternativeSelected = (_, _) -> {
    };
    private GuiScreen host;
    private Object drawnToken;
    private boolean closed, collapsed, layerDragging, materialsDragging;
    private float collapseNow, sidebarNow = 1;
    private long frameTime = -1;
    private String pressed;
    private int mouseX, mouseY;
    private Rect currentClip;
    public StructurePreviewPanel(StructurePreviewView view) {
        this.view = Objects.requireNonNull(view);
        var mc = Minecraft.getMinecraft();
        player = mc.player;
        world = mc.world;
        connection = mc.getConnection();
        container = player == null ? null : player.openContainer;
        window = container == null ? -1 : container.windowId;
        scene = new StructurePreviewScene(view);
    }

    private static float animate(float value, float target, float alpha) {
        float next = value + (target - value) * alpha;
        return Math.abs(target - next) < .0005f ? target : next;
    }

    private static void sprite(String path, float x, float y, int width, int height) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(GuiAtlas.texture("gui_structure_preview/" + path));
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, width, height, width, height);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private ItemStack optionStack(StructureDisplay display, int option) { return scene.item(display, option); }

    public void construction() {
        construction = true;
        view.scan = true;
    }

    public void construction(ItemStack tool, Supplier<NBTTagCompound> binding) {
        construction();
        builderTool = Objects.requireNonNull(tool);
        builderBinding = Objects.requireNonNull(binding);
    }

    public void onMaterialsRequested(Runnable listener) { materialsRequested = Objects.requireNonNull(listener); }

    public void onCellAlternativeSelected(BiConsumer<StructurePreviewModel.Cell, Integer> listener) {
        cellAlternativeSelected = Objects.requireNonNull(listener);
    }

    /** Tree double-click selects a requirement class; a preview click selects only its exact cell. */
    public void openMaterialChoices(StructureDisplay display) {
        if (!construction || display.options().size() <= 1) return;
        constructionChoice = display;
        constructionCell = null;
        view.select(display);
        lastConstructionPick = null;
        view.materialsOpen = true;
    }

    public void onAlternativeSelected(BiConsumer<StructureDisplay, Integer> listener) {
        alternativeSelected = Objects.requireNonNull(listener);
    }

    /** Embedded hosts publish the visible cells as their own native ingredient slots. */
    public void ingredientSlots(SlotRenderer renderer, Runnable clear) {
        ingredientSlots = Objects.requireNonNull(renderer);
        clearIngredients = Objects.requireNonNull(clear);
    }

    @FunctionalInterface
    public interface SlotRenderer {
        void render(int index, ItemStack stack, int x, int y);
    }

    public StructurePreviewView view() {
        return view;
    }

    public StructurePreviewScene scene() {
        return scene;
    }

    public boolean closed() {
        return closed;
    }

    public boolean ownerCurrent() {
        var mc = Minecraft.getMinecraft();
        return !closed && player != null && mc.player == player && mc.world == world && mc.getConnection() == connection
            && player.openContainer == container && container.windowId == window && player.isEntityAlive() && player.getHealth() > 0
            && (host == null || mc.currentScreen == host);
    }

    public boolean inputCurrent() {
        return host != null && ownerCurrent() && drawnToken == view.token() && view.inputCurrent();
    }

    public void tick() {
        if (!ownerCurrent()) close();
    }

    /** Width/height are retained in the public host contract; PM's panel itself has a fixed size. */
    public void draw(int width, int height, int mouseX, int mouseY, float partialTicks, boolean embedded) {
        if (width <= 0 || height <= 0) return;
        if (!ownerCurrent()) {
            close();
            return;
        }
        if (host == null) host = Minecraft.getMinecraft().currentScreen;
        if (host == null) {
            close();
            return;
        }
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        updateConstructionChoice();
        actions.clear();
        itemSlots.clear();
        boolean materialsVisible = view.materialsOpen;
        boolean pointer = mouseY >= 31 && mouseY < 195
            && !(!construction && view.picked() != null && new Rect(9, 31, 18, 181).contains(mouseX, mouseY))
            && !(materialsVisible && new Rect(29, 31, 134, 163).contains(mouseX, mouseY))
            && !(!view.threeDimensional && new Rect(165, 33, 10, 158).contains(mouseX, mouseY));
        scene.pointer(mouseX - SCENE_X, mouseY - SCENE_Y, pointer);
        sprite("base", 0, 0, WIDTH, HEIGHT);
        scene.drawAtCurrentOrigin(SCENE_X, SCENE_Y, 171, 200, partialTicks);
        if (embedded) PreviewEmbeddedInput.INSTANCE.mark(host, this);
        drawnToken = view.token();
        long now = Minecraft.getSystemTime();
        double dt = frameTime < 0 ? 0 : Math.clamp((now - frameTime) / 1000.0, 0, .25);
        frameTime = now;
        float alpha = (float) (1 - Math.exp(-dt / .10));
        collapseNow = animate(collapseNow, collapsed ? 1 : 0, alpha);
        sidebarNow = animate(sidebarNow, view.picked() == null ? 1 : 0, alpha);
        topBar();
        materials();
        if (!view.threeDimensional) layerSlider();
        bottomBar();
    }

    private void clipped(float x, float y, int width, int height, Runnable content) {
        currentClip = new Rect(x, y, width, height);
        try {
            clip.draw(x, y, width, height, content);
        } finally {
            currentClip = null;
        }
    }

    private void area(String id, float x, float y, int width, int height, List<String> tooltip, @Nullable Runnable run) {
        actions.add(new Action(id, new Rect(x, y, width, height), currentClip, tooltip, run));
    }

    private void button(String id, String normal, String hover, String down, float x, float y, int width, int height,
                        boolean selected, boolean enabled, PreviewUiText text, Runnable run) {
        var action = new Action(id, new Rect(x, y, width, height), currentClip, List.of(text.getLocal()), enabled ? run : null);
        boolean hovered = action.contains(mouseX, mouseY);
        sprite(hovered && id.equals(pressed) ? down : selected || hovered ? hover : normal, x, y, width, height);
        actions.add(action);
    }

    private void topBar() {
        clipped(7, 10, 115, 18, () -> {
            float y = 10 - 16 * collapseNow;
            sprite("top_machine_prefix_name_base", 7, y, 115, 18);
            var titleText = view.title();
            String title = titleText == null ? view.id().toString() : titleText.getLocal();
            GuiLabel.draw(Minecraft.getMinecraft().fontRenderer, title, 10, y + 5, 109, 10, 0xffffff, false);
            area("title", 7, y, 115, 18, List.of(title), null);
        });
        clipped(123, 10, 17, 19, () -> {
            float y = 10 - 15 * collapseNow;
            sprite("top_button_base_preview_reset", 123, y, 17, 17);
            button("reset", "top_button/self_resetting_default", "top_button/self_resetting_selected", "top_button/self_resetting_press",
                126, y + 15, 13, 5, false, true, PreviewUiText.Reset, scene.camera()::reset);
        });
        clipped(141, 10, 17, 19, () -> {
            float y = 10 - 15 * collapseNow;
            sprite("top_button_base_structural_forming_" + (view.formedPreview() ? "on" : "off"), 141, y, 17, 17);
            area("formingBase", 141, y, 17, 17, List.of(PreviewUiText.Formed.getLocal()), null);
            button("forming", "top_button/self_locking_default", "top_button/self_locking_selected", "top_button/self_locking_pressed",
                144, y + 15, 13, 5, view.formedPreview(), true, PreviewUiText.Formed,
                () -> view.formedPreview(drawnToken, !view.formedPreview()));
        });
        clipped(159, 10, 17, 18, () -> {
            float y = 10 - 15 * collapseNow;
            sprite("top_button_base_structural_information", 159, y, 17, 18);
            area("info", 159, y, 17, 18, List.of(PreviewUiText.Structure.getLocal(view.id()), PreviewUiText.Scan.getLocal(PreviewUiText.onOff(view.scan)),
                PreviewUiText.Mode.getLocal((view.threeDimensional ? PreviewUiText.ThreeD : PreviewUiText.Slice).getLocal()),
                PreviewUiText.Layer.getLocal(view.layer), PreviewUiText.Forming.getLocal(PreviewUiText.onOff(view.formedPreview()))), null);
        });
    }

    private void bottomBar() {
        clipped(98, 195, 78, 16, () -> {
            float x = 98 + 57 * collapseNow;
            sprite("down_button_base_on", x, 198, 62, 13);
            sprite(collapsed ? "down_button_base_on-led" : "down_button_base_off-led", x + 2, 201, 1, 7);
            bottomButton("place_projection", x + 5, 195, false, !collapsed, PreviewUiText.Project, this::project);
            bottomButton("material_preview_ui", x + 18, 195, view.materialsOpen, !collapsed, PreviewUiText.Materials, this::toggleMaterials);
            bottomButton("replace_block", x + 31, 195, view.wireframe, !collapsed, PreviewUiText.Wireframe, () -> view.wireframe = !view.wireframe);
            bottomButton("layer_preview", x + 44, 195, !view.threeDimensional, !collapsed, PreviewUiText.LayerPreview, () -> view.threeDimensional = !view.threeDimensional);
            bottomButton("component_switch", 165, 197, collapsed, true, PreviewUiText.Collapse, () -> collapsed = !collapsed);
        });
    }

    private void bottomButton(String id, float x, float y, boolean selected, boolean enabled, PreviewUiText text, Runnable run) {
        button(id, "down_button_default/" + id + "_default", "down_button_selected/" + id + "_selected", "down_button_pressed/" + id + "_pressed",
            x, y, 12, 14, selected, enabled, text, run);
    }

    private void project() {
        var anchor = construction ? view.anchor() : StructurePreviewScreensAnchor.current();
        view.anchor(anchor);
        var options = new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, false,
            view.threeDimensional ? null : new StructurePreviewModel.Layer(view.layer), 2048, 0, 4096, 64);
        boolean projected = construction && !builderTool.isEmpty()
            ? ProjectionInteractions.INSTANCE.projectBuilder(view.previews(), view.id(), view.settings(), anchor, options, builderTool, builderBinding.get())
            : view.previews().projectFor(view.id(), view.settings(), anchor, options, 30_000);
        if (projected && !construction) ProjectionInteractions.INSTANCE.follow();
    }

    private void toggleMaterials() {
        invalidateSlots();
        if (!construction) {
            view.materialsOpen = !view.materialsOpen;
            return;
        }
        boolean opening = !view.materialsOpen || constructionChoice != null;
        constructionChoice = null;
        constructionCell = null;
        lastConstructionPick = view.picked();
        view.materialsOpen = opening;
        if (opening) materialsRequested.run();
    }

    private void updateConstructionChoice() {
        if (!construction || view.model() == null || Objects.equals(lastConstructionPick, view.picked())) return;
        var picked = view.picked();
        lastConstructionPick = picked;
        if (picked != null && picked.display() != null && picked.display().options().size() > 1) {
            constructionChoice = picked.display();
            constructionCell = picked.position();
            view.materialsOpen = true;
        } else if (constructionChoice != null) {
            constructionChoice = null;
            constructionCell = null;
            view.materialsOpen = false;
        }
    }

    private void layerSlider() {
        var bounds = view.sceneBounds();
        if (bounds == null) return;
        sprite("right_layer_selection", 165, 33, 10, 158);
        int maximum = bounds.max().getY() - bounds.min().getY();
        int index = Math.clamp(view.layer - bounds.min().getY(), 0, maximum);
        int y = 44 + (int) Math.round(maximum == 0 ? 0 : 124.0 * (maximum - index) / maximum);
        boolean hover = new Rect(165, 33, 10, 158).contains(mouseX, mouseY);
        sprite(layerDragging ? "slider_press" : hover ? "slider_selected" : "slider_default", 167, y, 7, 12);
        area("layer", 165, 33, 10, 158, List.of(PreviewUiText.SliceY.getLocal(view.layer)), null);
    }

    private void updateLayer(int y) {
        var bounds = view.sceneBounds();
        if (bounds == null) return;
        int min = bounds.min().getY(), max = bounds.max().getY();
        view.layer = max - (int) Math.round(Math.clamp(y - 33 - 11 - 6, 0, 124) / 124.0 * (max - min));
    }

    private void materials() {
        if (construction) {
            constructionMaterials();
            return;
        }
        var selected = view.picked() == null ? null : view.picked().display();
        alternativesScroll.identity(view.picked());
        alternativesCount = 0;
        clipped(9, 31, 18, 181, () -> {
            float x = 9 - 18 * Math.max(collapseNow, sidebarNow);
            sprite("left_replace_preview", x, 31, 18, 181);
            if (selected == null) {
                slot("none", ItemStack.EMPTY, x, 31, false, List.of(PreviewUiText.NoSelection.getLocal(), PreviewUiText.SelectHint.getLocal()), null);
                return;
            }
            int choice = representative(selected);
            var stack = optionStack(selected, choice);
            slot("selected", stack, x, 31, false, List.of(), null);
            var candidates = new IntArrayList();
            for (int index = 0; index < selected.options().size(); index++)
                if (index != choice && !optionStack(selected, index).isEmpty()) candidates.add(index);
            alternativesCount = candidates.size();
            int row = 1;
            for (int index = alternativesScroll.first(candidates.size(), 9); index < candidates.size() && row <= 9; index++) {
                int option = candidates.getInt(index);
                var alternative = optionStack(selected, option);
                slot("option" + option, alternative, x, 31 + row++ * 18, false, List.of(), () -> {
                        if (view.alternative(drawnToken, selected, option))
                            alternativeSelected.accept(selected, option);
                    });
            }
        });
        if (view.materialsOpen && view.model() != null) materialGrid(completeMaterials(), null, null);
    }

    private void constructionMaterials() {
        updateConstructionChoice();
        var model = view.model();
        if (!view.materialsOpen || model == null) return;
        var display = constructionChoice;
        var cell = constructionCell;
        List<ConstructionMaterial> rows;
        if (display != null) {
            var choices = new ObjectArrayList<ConstructionMaterial>(display.options().size());
            for (int option = 0; option < display.options().size(); option++) choices.add(new ConstructionMaterial(display, option, 1));
            rows = choices;
        } else {
            rows = completeMaterials();
        }
        materialGrid(rows, display, cell);
    }

    private List<ConstructionMaterial> completeMaterials() {
        var model = Objects.requireNonNull(view.model());
        if (constructionMaterialsToken != view.token()) {
            constructionMaterialsToken = view.token();
            var counts = new Object2LongLinkedOpenHashMap<ConstructionChoice>();
            for (var line : model.bom())
                counts.addTo(new ConstructionChoice(line.display(), view.alternative(line.display())), line.count());
            for (var entry : view.settings().cellOptions().entrySet()) {
                var represented = view.at(entry.getKey());
                var choice = entry.getValue();
                if (represented == null || !choice.display().equals(represented.display())) continue;
                int global = view.alternative(choice.display());
                if (global != choice.option()) {
                    counts.addTo(new ConstructionChoice(choice.display(), global), -1);
                    counts.addTo(new ConstructionChoice(choice.display(), choice.option()), 1);
                }
            }
            var materials = new ObjectArrayList<ConstructionMaterial>(counts.size());
            for (var entry : counts.object2LongEntrySet())
                if (entry.getLongValue() > 0) materials.add(new ConstructionMaterial(entry.getKey().display, entry.getKey().option, entry.getLongValue()));
            materials.sort(Comparator.comparingLong(ConstructionMaterial::amount).reversed());
            constructionMaterials = List.copyOf(materials);
        }
        return constructionMaterials;
    }

    private void materialGrid(List<ConstructionMaterial> rows, @Nullable StructureDisplay display, @Nullable BlockPos cell) {
        sprite("base_material_preview_ui", 29, 31, 134, 163);
        materialsScroll.identity(new ConstructionChoice(display, 0));
        materialsCount = (rows.size() + MATERIAL_COLUMNS - 1) / MATERIAL_COLUMNS;
        clipped(MATERIAL_CONTENT.x, MATERIAL_CONTENT.y, (int) MATERIAL_CONTENT.width, (int) MATERIAL_CONTENT.height, () -> {
            int first = materialsScroll.first(materialsCount, MATERIAL_ROWS) * MATERIAL_COLUMNS;
            for (int visible = 0; visible < MATERIAL_COLUMNS * MATERIAL_ROWS && first + visible < rows.size(); visible++) {
                int index = first + visible;
                var row = rows.get(index);
                var stack = row.display == null ? ItemStack.EMPTY : optionStack(row.display, row.option);
                if (!stack.isEmpty()) stack.setCount((int) Math.min(Integer.MAX_VALUE, row.amount));
                int option = row.option;
                Runnable choose = display == null ? null : () -> chooseConstruction(display, cell, option);
                int slotX = 33 + visible % MATERIAL_COLUMNS * MATERIAL_STEP, slotY = 35 + visible / MATERIAL_COLUMNS * MATERIAL_STEP;
                slot("construction_material" + index, stack, slotX, slotY,
                    display == null, row.display == null ? List.of(PreviewUiText.Unknown.getLocal()) : List.of(), choose);
            }
        });
        int maximum = Math.max(0, materialsCount - MATERIAL_ROWS);
        int thumbY = 35 + (int) Math.round(maximum == 0 ? 0 : 143.0 * materialsScroll.first(materialsCount, MATERIAL_ROWS) / maximum);
        sprite(materialsDragging ? "slider_press" : MATERIAL_SCROLL.contains(mouseX, mouseY) ? "slider_selected" : "slider_default", 154, thumbY, 7, 12);
        area("materialsScroll", MATERIAL_SCROLL.x, MATERIAL_SCROLL.y, (int) MATERIAL_SCROLL.width, (int) MATERIAL_SCROLL.height, List.of(), null);
    }

    private void chooseConstruction(StructureDisplay display, @Nullable BlockPos position, int option) {
        var previousPick = view.picked();
        boolean changed;
        if (position == null) {
            changed = view.alternative(drawnToken, display, option);
            if (changed) alternativeSelected.accept(display, option);
        } else {
            var cell = view.at(position);
            changed = cell != null && display.equals(cell.display()) && view.alternativeCell(drawnToken, cell, option);
            if (changed) cellAlternativeSelected.accept(cell, option);
        }
        if (changed) {
            dismissMaterials();
            // Model rebuilding restores this same cell; that restoration is not another click.
            lastConstructionPick = previousPick;
        }
    }

    private record ConstructionChoice(@Nullable StructureDisplay display, int option) { }
    private record ConstructionMaterial(@Nullable StructureDisplay display, int option, long amount) { }

    private void slot(String id, ItemStack stack, float x, float y, boolean amount, List<String> tooltip, @Nullable Runnable run) {
        if (!amount && !stack.isEmpty()) stack.setCount(1);
        int index = itemSlots.size();
        while (displaySlots.size() <= index)
            displaySlots.add(new AppEngSlot(displayInventory, displaySlots.size(), 1, 1));
        var nativeSlot = displaySlots.get(index);
        nativeSlot.xPos = nativeSlot.yPos = 1;
        var bounds = new Rect(x, y, 18, 18);
        boolean fullyVisible = currentClip == null || x >= currentClip.x && y >= currentClip.y
            && x + 18 <= currentClip.x + currentClip.width && y + 18 <= currentClip.y + currentClip.height;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            GuiAtlas.state(0, 0, 73, 0, 18, 18);
            if (!stack.isEmpty() && fullyVisible && ingredientSlots == null) {
                RenderHelper.enableGUIStandardItemLighting();
                displayInventory.setItemDirect(index, stack);
                if (host instanceof PreviewNativeGui.Slots nativeSlots) nativeSlots.prototype_eng$renderSlot(nativeSlot);
                else {
                    // HEI RecipesGui is a GuiScreen, so use its ordinary native item renderer.
                    var mc = Minecraft.getMinecraft();
                    mc.getRenderItem().renderItemAndEffectIntoGUI(player, stack, 1, 1);
                    mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRenderer, stack, 1, 1, null);
                }
                RenderHelper.disableStandardItemLighting();
                if (bounds.contains(mouseX, mouseY)) {
                    GlStateManager.disableLighting();
                    GlStateManager.disableDepth();
                    Gui.drawRect(1, 1, 17, 17, 0x80ffffff);
                    GlStateManager.enableDepth();
                }
            }
        } finally {
            GlStateManager.popMatrix();
        }
        area(id, x, y, 18, 18, tooltip, run);
        if (!stack.isEmpty() && fullyVisible) {
            displayInventory.setItemDirect(index, stack);
            itemSlots.add(new ItemSlot(stack, nativeSlot, bounds, currentClip));
            if (ingredientSlots != null) ingredientSlots.render(index, stack, (int) x, (int) y);
        }
    }

    private int representative(StructureDisplay display) {
        var picked = view.picked();
        return picked != null && display.equals(picked.display()) ? view.alternative(picked) : view.alternative(display);
    }

    public boolean press(int x, int y, int button) {
        if (!inputCurrent()) return false;
        dismissMaterialsOutside(x, y);
        for (int i = actions.size() - 1; i >= 0; i--) {
            var action = actions.get(i);
            if (!action.contains(x, y)) continue;
            if (ingredientSlots != null && action.run == null && !itemAt(x, y).isEmpty()) return false;
            if (button == 0 && action.id.equals("layer")) {
                layerDragging = true;
                updateLayer(y);
            } else if (button == 0 && action.id.equals("materialsScroll")) {
                materialsDragging = true;
                updateMaterialsScroll(y);
            } else if (button == 0 && action.run != null) pressed = action.id;
            if (button == 0 && (layerDragging || materialsDragging || action.run != null))
                Minecraft.getMinecraft().getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1));
            return true;
        }
        if (view.materialsOpen && new Rect(29, 31, 134, 163).contains(x, y)) return true;
        var rect = scene.viewport();
        return scene.press(rect.x() + x - SCENE_X, rect.y() + y - SCENE_Y, button);
    }

    public boolean dismissMaterials() {
        if (!view.materialsOpen) return false;
        view.materialsOpen = false;
        constructionChoice = null;
        constructionCell = null;
        lastConstructionPick = view.picked();
        invalidateSlots();
        return true;
    }

    private void invalidateSlots() {
        itemSlots.clear();
        clearIngredients.run();
    }

    public void dismissMaterialsOutside(int x, int y) {
        if (!view.materialsOpen || MATERIAL_PANEL.contains(x, y)) return;
        for (var action : actions)
            if (action.id.equals("material_preview_ui") && action.contains(x, y)) return;
        dismissMaterials();
    }

    private void updateMaterialsScroll(int y) {
        materialsScroll.position((y - 41) / 143.0, materialsCount, MATERIAL_ROWS);
    }

    public boolean drag(int x, int y) {
        if (!inputCurrent()) return false;
        if (layerDragging) {
            updateLayer(y);
            return true;
        }
        if (materialsDragging) {
            updateMaterialsScroll(y);
            return true;
        }
        if (pressed != null) return true;
        var rect = scene.viewport();
        return scene.drag(rect.x() + x - SCENE_X, rect.y() + y - SCENE_Y);
    }

    public boolean release(int x, int y, int button) {
        if (!inputCurrent()) {
            pressed = null;
            layerDragging = false;
            materialsDragging = false;
            return false;
        }
        if (button == 0 && layerDragging) {
            layerDragging = false;
            updateLayer(y);
            return true;
        }
        if (button == 0 && materialsDragging) {
            materialsDragging = false;
            updateMaterialsScroll(y);
            return true;
        }
        if (button == 0 && pressed != null) {
            String previous = pressed;
            pressed = null;
            for (var action : actions)
                if (previous.equals(action.id) && action.contains(x, y) && action.run != null) {
                    action.run.run();
                    invalidateSlots();
                    break;
                }
            return true;
        }
        var rect = scene.viewport();
        boolean handled = scene.release(rect.x() + x - SCENE_X, rect.y() + y - SCENE_Y, button);
        if (handled) invalidateSlots();
        return handled;
    }

    public boolean wheel(int x, int y, int delta) {
        if (!inputCurrent() || delta == 0) return false;
        if (!construction && view.picked() != null && new Rect(9, 31, 18, 181).contains(x, y)) {
            alternativesScroll.wheel(delta, alternativesCount, 9);
            invalidateSlots();
            return true;
        }
        if (view.materialsOpen && new Rect(29, 31, 134, 163).contains(x, y)) {
            materialsScroll.wheel(delta, materialsCount, MATERIAL_ROWS);
            invalidateSlots();
            return true;
        }
        if (!view.threeDimensional && new Rect(165, 33, 10, 158).contains(x, y)) {
            var bounds = view.sceneBounds();
            if (bounds != null) view.layer = Math.clamp(view.layer + Integer.signum(delta), bounds.min().getY(), bounds.max().getY());
            return true;
        }
        for (var action : actions) if (action.contains(x, y)) return true;
        if (view.materialsOpen && new Rect(29, 31, 134, 163).contains(x, y)) return true;
        var rect = scene.viewport();
        return scene.wheel(rect.x() + x - SCENE_X, rect.y() + y - SCENE_Y, delta);
    }

    public List<String> tooltip(int x, int y) {
        var stack = itemAt(x, y);
        return stack.isEmpty() ? controlTooltip(x, y) : host.getItemToolTip(stack);
    }

    public List<String> controlTooltip(int x, int y) {
        if (!inputCurrent()) return List.of();
        for (int i = actions.size() - 1; i >= 0; i--) if (actions.get(i).contains(x, y)) return actions.get(i).tooltip;
        return List.of();
    }

    public boolean renderItemTooltip(int screenX, int screenY, int localX, int localY) {
        if (ingredientSlots != null) return false;
        var stack = itemAt(localX, localY);
        if (stack.isEmpty()) return false;
        ((PreviewNativeGui) host).prototype_eng$renderItemTooltip(stack, screenX, screenY);
        return true;
    }

    private ItemStack itemAt(int x, int y) {
        if (!inputCurrent()) return ItemStack.EMPTY;
        for (int i = itemSlots.size() - 1; i >= 0; i--) {
            var slot = itemSlots.get(i);
            if (slot.bounds.contains(x, y) && (slot.clip == null || slot.clip.contains(x, y))) return slot.stack;
        }
        return ItemStack.EMPTY;
    }

    public @Nullable Slot slotAt(int x, int y, int offsetX, int offsetY) {
        if (!inputCurrent()) return null;
        for (var item : itemSlots)
            if (item.bounds.contains(x, y) && (item.clip == null || item.clip.contains(x, y))) {
                item.slot.xPos = (int) item.bounds.x + offsetX + 1;
                item.slot.yPos = (int) item.bounds.y + offsetY + 1;
                return item.slot;
            }
        return null;
    }

    public boolean ownsSlot(@Nullable Slot slot) {
        return slot != null && displaySlots.contains(slot);
    }

    private record ItemSlot(ItemStack stack, Slot slot, Rect bounds, @Nullable Rect clip) { }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        invalidateSlots();
        PreviewEmbeddedInput.INSTANCE.remove(this);
        scene.close();
    }

    private record Rect(float x, float y, float width, float height) {
        boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }

    private record Action(String id, Rect bounds, @Nullable Rect clip, List<String> tooltip, @Nullable Runnable run) {
        boolean contains(int x, int y) {
            return bounds.contains(x, y) && (clip == null || clip.contains(x, y));
        }
    }
}
