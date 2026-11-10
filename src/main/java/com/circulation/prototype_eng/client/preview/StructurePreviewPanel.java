package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import com.circulation.prototype_eng.client.gui.GuiAtlas;
import com.circulation.prototype_eng.client.gui.GuiClip;
import com.circulation.prototype_eng.client.gui.GuiLabel;
import com.circulation.prototype_eng.localization.PreviewUiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
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
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** PM's implemented 184x220 preview UI, shared unchanged by the standalone and JEI hosts. */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class StructurePreviewPanel implements AutoCloseable {
    public static final int WIDTH = 184, HEIGHT = 220;
    static final int SCENE_X = 6, SCENE_Y = 13;
    private final StructurePreviewView view;
    private final StructurePreviewScene scene;
    private final EntityPlayer player;
    private final Object world, connection;
    private final Container container;
    private final int window;
    private final GuiClip clip = new GuiClip();
    private final ObjectArrayList<Action> actions = new ObjectArrayList<>();
    private boolean construction;
    private BiConsumer<StructureDisplay, Integer> alternativeSelected = (_, _) -> {
    };
    private GuiScreen host;
    private Object drawnToken;
    private boolean closed, collapsed, layerDragging;
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

    private static ItemStack optionStack(StructureDisplay.BlockOption option) {
        var block = ForgeRegistries.BLOCKS.getValue(option.blockId());
        return block == null ? ItemStack.EMPTY : new ItemStack(block, 1, option.meta());
    }

    private static String shortName(StructureDisplay display) {
        if (display.options().size() != 1) return display.key();
        var option = display.options().getFirst();
        return option.blockId() + (option.meta() == 0 ? "" : "@" + option.meta());
    }

    private static String optionKey(StructureDisplay.BlockOption option) {
        return "state:" + BuilderState.optionKey(option);
    }

    public void construction() {
        construction = true;
        view.scan = true;
    }

    public void onAlternativeSelected(BiConsumer<StructureDisplay, Integer> listener) {
        alternativeSelected = Objects.requireNonNull(listener);
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
        actions.clear();
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
            var model = view.model();
            var titleText = model == null ? null : model.title();
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
            bottomButton("material_preview_ui", x + 18, 195, view.materialsOpen, !collapsed, PreviewUiText.Materials, () -> view.materialsOpen = !view.materialsOpen);
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
        if (view.previews().project(view.id(), view.settings(), anchor,
            new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, false, null, 2048, 0, 4096, 64)))
            if (!construction) ProjectionInteractions.INSTANCE.follow();
    }

    private void layerSlider() {
        var model = view.model();
        if (model == null) return;
        sprite("right_layer_selection", 165, 33, 10, 158);
        int maximum = model.bounds().max().getY() - model.bounds().min().getY();
        int index = Math.clamp(view.layer - model.bounds().min().getY(), 0, maximum);
        int y = 44 + (int) Math.round(maximum == 0 ? 0 : 124.0 * index / maximum);
        boolean hover = new Rect(165, 33, 10, 158).contains(mouseX, mouseY);
        sprite(layerDragging ? "slider_press" : hover ? "slider_selected" : "slider_default", 167, y, 7, 12);
        area("layer", 165, 33, 10, 158, List.of(PreviewUiText.SliceY.getLocal(view.layer)), null);
    }

    private void updateLayer(int y) {
        var model = view.model();
        if (model == null) return;
        int min = model.bounds().min().getY(), max = model.bounds().max().getY();
        view.layer = min + (int) Math.round(Math.clamp(y - 33 - 11 - 6, 0, 124) / 124.0 * (max - min));
    }

    private void materials() {
        var selected = view.picked() == null ? null : view.picked().display();
        clipped(9, 31, 18, 181, () -> {
            float x = 9 - 18 * Math.max(collapseNow, sidebarNow);
            sprite("left_replace_preview", x, 31, 18, 181);
            if (selected == null) {
                slot("none", ItemStack.EMPTY, x, 31, false, List.of(PreviewUiText.NoSelection.getLocal(), PreviewUiText.SelectHint.getLocal()), null);
                return;
            }
            int choice = representative(selected);
            var stack = optionStack(selected.options().get(choice));
            boolean any = selected.options().size() > 1;
            var selectedTooltip = new ObjectArrayList<>(any
                ? List.of(PreviewUiText.CurrentSelection.getLocal(stack.getDisplayName()), PreviewUiText.ChooseOne.getLocal(selected.options().size()), PreviewUiText.Key.getLocal(selected.key()))
                : List.of(stack.isEmpty() ? PreviewUiText.Empty.getLocal() : stack.getDisplayName(), PreviewUiText.Requirement.getLocal(shortName(selected)), PreviewUiText.Key.getLocal(selected.key())));
            if (!selected.complete()) selectedTooltip.add(PreviewUiText.Status.getLocal(PreviewUiText.Unknown.getLocal()));
            slot("selected", stack, x, 31, false, selectedTooltip, null);
            int row = 1;
            for (int index = 0; index < selected.options().size() && row <= 9; index++) {
                if (index == choice) continue;
                var alternative = optionStack(selected.options().get(index));
                if (alternative.isEmpty()) continue;
                int option = index;
                slot("option" + index, alternative, x, 31 + row++ * 18, false,
                    List.of(PreviewUiText.Candidate.getLocal(alternative.getDisplayName()), PreviewUiText.ClickToSelect.getLocal(),
                        PreviewUiText.Key.getLocal(optionKey(selected.options().get(index)))), () -> {
                        if (view.alternative(drawnToken, selected, option))
                            alternativeSelected.accept(selected, option);
                    });
            }
        });
        var model = view.model();
        if ((!view.materialsOpen && !(construction && selected != null)) || model == null) return;
        sprite("base_material_preview_ui", 29, 31, 134, 163);
        var bom = selected == null ? new ObjectArrayList<>(model.bom())
            : new ObjectArrayList<>(List.of(new StructurePreviewModel.BomLine(selected, 1)));
        if (construction && selected == null && view.scan) {
            bom.clear();
            for (var material : view.materials(StructurePreviewPanel::shortName, PreviewUiText.Unknown.getLocal()))
                if (material.mismatch() > 0)
                    bom.add(new StructurePreviewModel.BomLine(material.display(), material.mismatch()));
        }
        bom.sort(Comparator.comparingLong(StructurePreviewModel.BomLine::count).reversed());
        int row = 0;
        for (var line : bom) {
            var display = line.display();
            if (display == null || selected != null && !selected.equals(display)) continue;
            var stack = optionStack(display.options().get(representative(display)));
            if (stack.isEmpty()) continue;
            int count = selected == null ? (int) Math.min(Integer.MAX_VALUE, line.count()) : 1;
            stack.setCount(count);
            var tooltip = new ObjectArrayList<String>();
            tooltip.add(stack.getDisplayName());
            if (!display.complete()) tooltip.add(PreviewUiText.Status.getLocal(PreviewUiText.Unknown.getLocal()));
            if (selected == null) {
                tooltip.add((construction ? PreviewUiText.Remaining : PreviewUiText.Required).getLocal(count));
                tooltip.add(PreviewUiText.Key.getLocal(display.key()));
            } else {
                tooltip.add(PreviewUiText.Requirement.getLocal(shortName(display)));
                var status = view.pickedStatus();
                if (status != null && display.complete()) tooltip.add(PreviewUiText.Status.getLocal(switch (status) {
                    case DISPLAY_MATCH -> PreviewUiText.Match.getLocal();
                    case MISSING -> PreviewUiText.Missing.getLocal();
                    case DIFFERENT -> PreviewUiText.Wrong.getLocal();
                    case UNLOADED -> PreviewUiText.Unloaded.getLocal();
                    case UNKNOWN -> PreviewUiText.Unknown.getLocal();
                }));
                tooltip.add(PreviewUiText.Count.getLocal(count));
            }
            if (display.options().size() > 1) tooltip.add(PreviewUiText.Replaceable.getLocal(display.options().size()));
            slot("material" + row, stack, 33, 35 + row * 20, true, tooltip, null);
            if (++row >= 24) break;
        }
    }

    private void slot(String id, ItemStack stack, float x, float y, boolean amount, List<String> tooltip, @Nullable Runnable run) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            GuiAtlas.state(0, 0, 73, 0, 18, 18);
            if (!stack.isEmpty()) {
                RenderHelper.enableGUIStandardItemLighting();
                Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, 1, 1);
                RenderHelper.disableStandardItemLighting();
                if (amount) PreviewAmountText.draw(Minecraft.getMinecraft().fontRenderer, stack.getCount());
            }
        } finally {
            GlStateManager.popMatrix();
        }
        area(id, x, y, 18, 18, tooltip, run);
    }

    private int representative(StructureDisplay display) {
        return view.settings().displayOptions().getOrDefault(display, 0);
    }

    public boolean press(int x, int y, int button) {
        if (!inputCurrent()) return false;
        for (int i = actions.size() - 1; i >= 0; i--) {
            var action = actions.get(i);
            if (!action.contains(x, y)) continue;
            if (button == 0 && action.id.equals("layer")) {
                layerDragging = true;
                updateLayer(y);
            } else if (button == 0 && action.run != null) pressed = action.id;
            if (button == 0 && (layerDragging || action.run != null))
                Minecraft.getMinecraft().getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1));
            return true;
        }
        if (view.materialsOpen && new Rect(29, 31, 134, 163).contains(x, y)) return true;
        var rect = scene.viewport();
        return scene.press(rect.x() + x - SCENE_X, rect.y() + y - SCENE_Y, button);
    }

    public boolean drag(int x, int y) {
        if (!inputCurrent()) return false;
        if (layerDragging) {
            updateLayer(y);
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
            return false;
        }
        if (button == 0 && layerDragging) {
            layerDragging = false;
            updateLayer(y);
            return true;
        }
        if (button == 0 && pressed != null) {
            String previous = pressed;
            pressed = null;
            for (var action : actions)
                if (previous.equals(action.id) && action.contains(x, y) && action.run != null) {
                    action.run.run();
                    break;
                }
            return true;
        }
        var rect = scene.viewport();
        return scene.release(rect.x() + x - SCENE_X, rect.y() + y - SCENE_Y, button);
    }

    public boolean wheel(int x, int y, int delta) {
        if (!inputCurrent() || delta == 0) return false;
        for (var action : actions) if (action.contains(x, y)) return true;
        if (view.materialsOpen && new Rect(29, 31, 134, 163).contains(x, y)) return true;
        var rect = scene.viewport();
        return scene.wheel(rect.x() + x - SCENE_X, rect.y() + y - SCENE_Y, delta);
    }

    public List<String> tooltip(int x, int y) {
        if (!inputCurrent()) return List.of();
        for (int i = actions.size() - 1; i >= 0; i--) if (actions.get(i).contains(x, y)) return actions.get(i).tooltip;
        return List.of();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
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
