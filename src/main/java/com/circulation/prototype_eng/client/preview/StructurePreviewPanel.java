package com.circulation.prototype_eng.client.preview;

import net.minecraftforge.fml.common.registry.ForgeRegistries;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Composable read-only scene/material control, with native text field, folding menus and visual feedback. */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class StructurePreviewPanel implements AutoCloseable {
    static final int SCENE_X = 4, SCENE_Y = 22;
    private static final ResourceLocation BASE = texture("base");
    private final StructurePreviewView view;
    private final StructurePreviewScene scene;
    private final EntityPlayer player;
    private final Object world, connection;
    private final Container container;
    private final int window;
    private GuiScreen host;
    private GuiTextField search;
    private int searchWidth;
    private final ObjectArrayList<Action> actions = new ObjectArrayList<>();
    private final Object2ObjectOpenHashMap<Action, List<String>> tooltips = new Object2ObjectOpenHashMap<>();
    private int width, height, materialOffset, occurrenceOffset;
    private int sceneHeight;
    private int mouseX, mouseY;
    private Object drawnToken;
    private boolean closed;
    private double materialsAmount = 1;
    private long frameTime;
    private StructurePreviewText pressedText;
    private long pressedUntil;
    private record Action(int x, int y, int width, int height, StructurePreviewText text, boolean selected, Runnable run) {
        boolean contains(int px, int py) { return px >= x && px < x + width && py >= y && py < y + height; }
    }
    public StructurePreviewPanel(StructurePreviewView view) {
        this.view = Objects.requireNonNull(view);
        var mc = Minecraft.getMinecraft();
        player = mc.player; world = mc.world; connection = mc.getConnection();
        container = player == null ? null : player.openContainer;
        window = container == null ? -1 : container.windowId;
        scene = new StructurePreviewScene(view);
        search = new GuiTextField(0, Minecraft.getMinecraft().fontRenderer, 4, 0, 100, 12);
        search.setMaxStringLength(256); search.setText(view.search);
    }
    public StructurePreviewView view() { return view; }
    public StructurePreviewScene scene() { return scene; }
    public boolean closed() { return closed; }
    public boolean ownerCurrent() {
        var mc = Minecraft.getMinecraft();
        return !closed && player != null && mc.player == player && mc.world == world && mc.getConnection() == connection
            && player.openContainer == container && container.windowId == window && player.isEntityAlive() && player.getHealth() > 0
            && (host == null || mc.currentScreen == host);
    }
    public boolean inputCurrent() { return host != null && ownerCurrent() && drawnToken == view.token() && view.inputCurrent(); }
    public void tick() { if (!ownerCurrent()) close(); else search.updateCursorCounter(); }
    private static ResourceLocation texture(String name) { return new ResourceLocation(Tags.MOD_ID, "textures/gui/gui_structure_preview/" + name + ".png"); }

    /** Draw using the host's current GUI origin; JEI already translates to its recipe origin. */
    public void draw(int width, int height, int mouseX, int mouseY, float partialTicks, boolean embedded) {
        if (!ownerCurrent()) { close(); return; }
        if (host == null) host = Minecraft.getMinecraft().currentScreen;
        if (host == null) { close(); return; }
        this.width = width; this.height = height; this.mouseX = mouseX; this.mouseY = mouseY;
        actions.clear(); tooltips.clear();
        var mc = Minecraft.getMinecraft(); var font = mc.fontRenderer;
        mc.getTextureManager().bindTexture(BASE); GlStateManager.color(1, 1, 1, 1);
        Gui.drawScaledCustomSizeModalRect(0, 0, 0, 0, 184, 220, width, height, 184, 220);
        long now = System.currentTimeMillis();
        double dt = frameTime == 0 ? 0 : Math.clamp((now - frameTime) / 1000.0, 0, 0.25); frameTime = now;
        materialsAmount += ((view.materialsOpen ? 1 : 0) - materialsAmount) * (1 - Math.exp(-dt / 0.1));
        sceneHeight = Math.max(42, height - 50 - (int) ((height * 0.42) * materialsAmount));
        scene.drawAtCurrentOrigin(4, 22, Math.max(1, width - 8), sceneHeight, partialTicks);
        if (embedded) PreviewEmbeddedInput.INSTANCE.mark(mc.currentScreen, this);
        drawnToken = view.token();
        var model = view.model();
        var localizedTitle = model == null ? null : model.title();
        String title = localizedTitle != null ? localizedTitle.getLocal() : view.id().toString();
        font.drawString(font.trimStringToWidth(title, width - 102), 4, 5, 0xeeeeee);
        button(width - 96, 2, 46, 16, StructurePreviewText.ViewOptions, view.optionsOpen, () -> view.optionsOpen = !view.optionsOpen);
        button(width - 48, 2, 44, 16, StructurePreviewText.Reset, false, () -> scene.camera().reset());
        int controlY = 23 + sceneHeight;
        int slot = Math.max(28, (width - 8) / 5);
        button(4, controlY, slot, 16, StructurePreviewText.View3D, view.threeDimensional, () -> view.threeDimensional = !view.threeDimensional);
        button(4 + slot, controlY, slot, 16, StructurePreviewText.Materials, view.materialsOpen, () -> view.materialsOpen = !view.materialsOpen);
        button(4 + slot * 2, controlY, slot, 16, StructurePreviewText.Scan, view.scan, () -> view.scan = !view.scan);
        button(4 + slot * 3, controlY, slot, 16, StructurePreviewText.Alternatives, view.alternativesOpen, () -> { view.alternativesOpen = !view.alternativesOpen; view.occurrencesOpen = false; view.materialsOpen = true; });
        button(4 + slot * 4, controlY, width - 8 - slot * 4, 16, StructurePreviewText.Slices, view.occurrencesOpen, () -> { view.occurrencesOpen = !view.occurrencesOpen; view.alternativesOpen = false; view.materialsOpen = true; });
        int top = controlY + 19;
        if (model == null) {
            var state = view.state();
            font.drawString((state == StructurePreviews.State.BUILDING ? StructurePreviewText.Building
                : state == StructurePreviews.State.FAILED ? StructurePreviewText.Failed : StructurePreviewText.Unavailable).getLocal(), 4, top, 0xffffff);
        } else if (materialsAmount > 0.8) {
            if (view.occurrencesOpen) occurrences(top);
            else if (view.alternativesOpen) alternatives(top);
            else materials(top);
        }
        if (model != null && !view.threeDimensional) {
            int y = 24;
            button(width - 62, y, 18, 16, StructurePreviewText.Layers, false, () -> view.layer = Math.max(model.bounds().min().getY(), view.layer - 1));
            font.drawString(Integer.toString(view.layer), width - 40, y + 4, 0xffffff);
            button(width - 22, y, 18, 16, StructurePreviewText.Layers, true, () -> view.layer = Math.min(model.bounds().max().getY(), view.layer + 1));
        }
        if (view.optionsOpen) {
            button(width - 96, 43, 92, 16, StructurePreviewText.AutoRotate, view.autoRotate, () -> view.autoRotate = !view.autoRotate);
            button(width - 96, 61, 92, 16, StructurePreviewText.Wireframe, view.wireframe, () -> view.wireframe = !view.wireframe);
        }
        button(4, height - 20, (width - 12) / 2, 16, StructurePreviewText.Project, false,
            () -> { var anchor = StructurePreviewScreensAnchor.current(); view.anchor(anchor);
                if (view.previews().project(view.id(), view.settings(), anchor,
                new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, false,
                    view.threeDimensional ? null : new StructurePreviewModel.Layer(view.layer), 2048, view.scan ? 2048 : 0, 4096, 64))) ProjectionInteractions.INSTANCE.follow(); });
        button(width / 2 + 2, height - 20, width / 2 - 6, 16, StructurePreviewText.Stop, false, () -> view.previews().stopProjection());
    }
    private void button(int x, int y, int w, int h, StructurePreviewText text, boolean selected, Runnable callback) {
        var action = new Action(x, y, w, h, text, selected, callback); actions.add(action);
        var mc = Minecraft.getMinecraft();
        String name = pressedText == text && System.currentTimeMillis() < pressedUntil ? "self_resetting_press"
            : action.contains(mouseX, mouseY) ? "self_resetting_selected" : "self_resetting_default";
        mc.getTextureManager().bindTexture(texture("top_button/" + name)); GlStateManager.color(1, 1, 1, 1);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 13, 5, w, h, 13, 5);
        var label = mc.fontRenderer.trimStringToWidth(text.getLocal(), Math.max(0, w - 2));
        mc.fontRenderer.drawString(label, x + Math.max(1, (w - mc.fontRenderer.getStringWidth(label)) / 2), y + 4, selected ? 0x80d8ff : 0xffffff);
    }
    private void materials(int top) {
        var font = Minecraft.getMinecraft().fontRenderer;
        if (search.y != top || searchWidth != Math.max(30, width - 72)) {
            boolean focused = search.isFocused();
            searchWidth = Math.max(30, width - 72);
            search = new GuiTextField(0, font, 4, top, searchWidth, 12);
            search.setMaxStringLength(256); search.setText(view.search); search.setFocused(focused);
        }
        search.drawTextBox();
        button(width - 65, top, 61, 14, switch (view.sort) {
            case ISSUES -> StructurePreviewText.SortIssues; case COUNT -> StructurePreviewText.SortCount; case NAME -> StructurePreviewText.SortName;
        }, false, () -> { view.sort = StructurePreviewView.Sort.values()[(view.sort.ordinal() + 1) % 3]; materialOffset = 0; });
        var flags = new boolean[] {view.showMatch, view.showMismatch, view.showUnloaded, view.showUnknown};
        var texts = new StructurePreviewText[] {StructurePreviewText.Match, StructurePreviewText.Mismatch, StructurePreviewText.Unloaded, StructurePreviewText.Unknown};
        int stride = (width - 8) / 4;
        for (int i = 0; i < 4; i++) {
            int flag = i;
            button(4 + i * stride, top + 16, stride, 14, texts[i], flags[i], () -> {
                switch (flag) { case 0 -> view.showMatch = !view.showMatch; case 1 -> view.showMismatch = !view.showMismatch;
                    case 2 -> view.showUnloaded = !view.showUnloaded; case 3 -> view.showUnknown = !view.showUnknown; }
                materialOffset = 0;
            });
        }
        var rows = view.materials(this::displayName, StructurePreviewText.Unknown.getLocal());
        int visible = Math.max(0, (height - 23 - top - 32) / 18);
        materialOffset = Math.clamp(materialOffset, 0, Math.max(0, rows.size() - visible));
        for (int i = 0; i < visible && materialOffset + i < rows.size(); i++) {
            var row = rows.get(materialOffset + i); int y = top + 32 + i * 18;
            if (Objects.equals(view.selected(), row.display())) Gui.drawRect(4, y, width - 4, y + 17, 0x884080aa);
            var stack = stack(row.display());
            if (!stack.isEmpty()) Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, 4, y);
            var count = Long.toString(row.count()); int cw = font.getStringWidth(count);
            font.drawString(font.trimStringToWidth(row.name(), Math.max(1, width - cw - 28)), 23, y + 4, 0xffffff);
            font.drawString(count, width - cw - 5, y + 4, 0xffffff);
            var token = drawnToken;
            var action = new Action(4, y, width - 8, 17, StructurePreviewText.Selected, false,
                () -> { if (token == view.token() && view.model() != null) view.select(row.display()); });
            actions.add(action);
            tooltips.put(action, List.of(row.name(), StructurePreviewText.Counts.getLocal(row.count()), StructurePreviewText.Issues.getLocal(row.mismatch(), row.unloaded(), row.unknown())));
        }
    }
    private void alternatives(int top) {
        var selected = view.selected();
        if (selected == null) { Minecraft.getMinecraft().fontRenderer.drawString(StructurePreviewText.Selected.getLocal(), 4, top, 0xffffff); return; }
        int columns = Math.max(1, (width - 8) / 22);
        int visibleRows = Math.max(1, (height - 23 - top) / 22);
        materialOffset = Math.clamp(materialOffset, 0, Math.max(0, (selected.options().size() + columns - 1) / columns - visibleRows));
        for (int index = materialOffset * columns; index < selected.options().size(); index++) {
            int px = 4 + index % columns * 22, py = top + (index / columns - materialOffset) * 22;
            if (py + 20 > height - 23) break;
            int choice = index; var token = drawnToken;
            Gui.drawRect(px, py, px + 20, py + 20, choice == representative(selected) ? 0xff4080aa : 0xff777777);
            var stack = optionStack(selected.options().get(choice));
            if (!stack.isEmpty()) Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, px + 2, py + 2);
            var action = new Action(px, py, 20, 20, StructurePreviewText.Alternatives, false, () -> view.alternative(token, selected, choice));
            actions.add(action);
            var option = selected.options().get(choice);
            tooltips.put(action, List.of(stack.isEmpty() ? option.blockId().toString() : stack.getDisplayName(), option.blockId() + " @ " + option.meta()));
        }
    }
    private void occurrences(int top) {
        var model = view.model(); if (model == null) return;
        var slices = new ObjectArrayList<StructurePreviewModel.Occurrence>();
        for (var occurrence : model.occurrences()) if (model.generation().nodes().get(occurrence.node()).kind() == StructurePreviewProvider.Kind.SLICE) slices.add(occurrence);
        int visible = Math.max(0, (height - 23 - top) / 20);
        occurrenceOffset = Math.clamp(occurrenceOffset, 0, Math.max(0, slices.size() - visible));
        for (int i = 0; i < visible && occurrenceOffset + i < slices.size(); i++) {
            var occurrence = slices.get(occurrenceOffset + i); var node = model.generation().nodes().get(occurrence.node());
            int y = top + i * 20; var token = drawnToken;
            String title = node.id() + " " + path(occurrence.path());
            Minecraft.getMinecraft().fontRenderer.drawString(Minecraft.getMinecraft().fontRenderer.trimStringToWidth(title, width - 64), 4, y + 4, 0xffffff);
            button(width - 62, y, 18, 16, StructurePreviewText.Slices, false, () -> view.slices(token, occurrence.path(), Math.max(node.minCount(), occurrence.selectedSlices() - 1)));
            Minecraft.getMinecraft().fontRenderer.drawString(Integer.toString(occurrence.selectedSlices()), width - 40, y + 4, 0xffffff);
            button(width - 22, y, 18, 16, StructurePreviewText.Slices, true, () -> view.slices(token, occurrence.path(), Math.min(node.maxCount(), occurrence.selectedSlices() + 1)));
        }
    }
    public static String path(StructurePreviewPath path) {
        var edges = new IntArrayList();
        for (var p = path; p.parent() != null; p = p.parent()) edges.add(p.childIndex());
        var result = new StringBuilder("/");
        for (int i = edges.size() - 1; i >= 0; i--) { if (i != edges.size() - 1) result.append('/'); result.append(edges.getInt(i)); }
        return result.toString();
    }
    private int representative(StructureDisplay display) {
        var explicit = view.settings().displayOptions().get(display);
        if (explicit != null) return explicit;
        for (int index = 0; index < display.options().size(); index++) {
            var option = display.options().get(index);
            if (ForgeRegistries.BLOCKS.containsKey(option.blockId())
                && ForgeRegistries.BLOCKS.getValue(option.blockId()) != null) return index;
        }
        return 0;
    }
    private ItemStack stack(@Nullable StructureDisplay display) { return display == null ? ItemStack.EMPTY : optionStack(display.options().get(representative(display))); }
    private static ItemStack optionStack(StructureDisplay.BlockOption option) {
        if (!ForgeRegistries.BLOCKS.containsKey(option.blockId())) return ItemStack.EMPTY;
        var block = ForgeRegistries.BLOCKS.getValue(option.blockId());
        return block == null ? ItemStack.EMPTY : new ItemStack(block, 1, option.meta());
    }
    private String displayName(StructureDisplay display) { var stack = stack(display); return stack.isEmpty() ? display.key() : stack.getDisplayName(); }
    public boolean press(int x, int y, int button) {
        if (!inputCurrent() || x < 0 || y < 0 || x >= width || y >= height) return false;
        if (button == 0) {
            search.mouseClicked(x, y, button);
            for (var action : actions) if (action.contains(x, y)) {
                pressedText = action.text; pressedUntil = System.currentTimeMillis() + 120;
                if (drawnToken == view.token()) action.run.run(); return true;
            }
        }
        var rect = scene.viewport();
        return scene.press(rect.x() + x - 4, rect.y() + y - 22, button);
    }
    public boolean drag(int x, int y) { if (!inputCurrent()) return false; var rect = scene.viewport(); return scene.drag(rect.x() + x - 4, rect.y() + y - 22); }
    public boolean release(int x, int y, int button) { if (!inputCurrent()) return false; var rect = scene.viewport(); return scene.release(rect.x() + x - 4, rect.y() + y - 22, button); }
    public boolean wheel(int x, int y, int delta) {
        if (!inputCurrent() || delta == 0 || x < 0 || y < 0 || x >= width || y >= height) return false;
        var rect = scene.viewport();
        if (scene.wheel(rect.x() + x - 4, rect.y() + y - 22, delta)) return true;
        if (view.materialsOpen && y > sceneHeight + 40 && y < height - 23) {
            if (view.occurrencesOpen) occurrenceOffset = Math.max(0, occurrenceOffset - Integer.signum(delta));
            else materialOffset = Math.max(0, materialOffset - Integer.signum(delta));
            return true;
        }
        return false;
    }
    public boolean key(char typed, int key) {
        if (!inputCurrent() || !search.isFocused() || !view.materialsOpen || view.alternativesOpen || view.occurrencesOpen) return false;
        if (!search.textboxKeyTyped(typed, key)) return false;
        view.search = search.getText(); materialOffset = 0; return true;
    }
    public List<String> tooltip(int x, int y) {
        if (!inputCurrent()) return List.of();
        for (var action : actions) if (action.contains(x, y)) return tooltips.getOrDefault(action, List.of(action.text.getLocal()));
        var selected = view.picked();
        if (selected != null && y >= 22 && y < sceneHeight + 22) {
            var p = selected.position();
            return List.of(StructurePreviewText.Position.getLocal(p.getX(), p.getY(), p.getZ()), selected.display() == null ? StructurePreviewText.Unknown.getLocal() : displayName(selected.display()));
        }
        return List.of();
    }
    @Override public void close() { if (closed) return; closed = true; PreviewEmbeddedInput.INSTANCE.remove(this); scene.close(); }
}
