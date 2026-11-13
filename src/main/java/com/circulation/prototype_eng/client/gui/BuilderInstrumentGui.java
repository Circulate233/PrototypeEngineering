package com.circulation.prototype_eng.client.gui;

import ae2.api.orientation.BlockOrientation;
import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.PreviewNativeGui;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.ClickType;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;
import com.circulation.prototype_eng.container.StructureToolContainer;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import com.circulation.prototype_eng.structure.tool.ConstructionTarget;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Original PM builder panel: preview at (4,2), clipped tree and task controls. */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class BuilderInstrumentGui extends AEBaseGui<StructureToolContainer> implements PreviewNativeGui {
    private final BuilderStructureTree tree;
    private StructurePreviewPanel preview;
    private ResourceLocation id;
    private String appliedSelections = "";
    private boolean selectionsPending;
    private BlockOrientation orientation;
    private int pressedSetting = -1;

    @Override
    public void prototype_eng$renderItemTooltip(ItemStack stack, int x, int y) {
        renderToolTip(stack, x, y);
    }

    public BuilderInstrumentGui(StructureToolContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 228;
        tree = new BuilderStructureTree(container, positions -> {
            if (preview != null) preview.scene().highlighted(positions);
        });
        tree.onMaterialReplacement(display -> {
            if (preview != null) preview.openMaterialChoices(display);
        });
        tree.selections(() -> preview == null ? null : preview.view().settings());
        tree.machineTitle(() -> {
            if (preview != null && preview.view().machine() != null) return preview.view().machine().displayName();
            var anchor = mc.world == null ? null : ConstructionTarget.resolve(mc.world, BuilderState.position(container.snapshot()));
            return anchor == null ? null : anchor.name();
        });
        tree.onSliceChanged((key, count) -> {
            if (preview == null) return;
            var path = BuilderState.slicePath(key);
            var view = preview.view();
            if (path != null && view.slices(view.token(), path, count)) container.action("slice", key, Integer.toString(count));
        });
    }

    private void refresh() {
        var tag = container.snapshot();
        String name = tag.getString("BoundStructureId");
        if (!tag.getBoolean("Bound") || name.isBlank()) {
            if (preview != null) preview.close();
            preview = null;
            id = null;
            return;
        }
        var next = new ResourceLocation(name);
        var anchor = BuilderState.position(tag);
        var host = mc.world != null && mc.world.provider.getDimension() == tag.getInteger("BoundDim")
            ? ConstructionTarget.resolve(mc.world, anchor) : null;
        var nextOrientation = host == null ? BlockOrientation.NORTH_UP : host.orientation();
        if (next.equals(id) && nextOrientation == orientation) {
            preview.view().anchor(anchor);
            applySelections();
            return;
        }
        var oldCamera = preview == null ? null : preview.scene().camera();
        if (preview != null) preview.close();
        id = next;
        orientation = nextOrientation;
        var machineId = tag.getString("BoundMachineId");
        var machineType = machineId.isBlank() ? null : MachineTypeRegistry.INSTANCE.get(new ResourceLocation(machineId));
        if (machineType != null && !machineType.structureId().equals(id)) machineType = null;
        var view = new StructurePreviewView(StructurePreviews.client(), id,
            new StructurePreviewSettings(orientation, BuilderState.previewSlices(tag), 65536, 1048576), machineType);
        view.anchor(anchor);
        view.wireframe = false;
        preview = new StructurePreviewPanel(view);
        preview.construction(container.tool(), container::snapshot);
        preview.onMaterialsRequested(tree::openMaterials);
        if (oldCamera != null) preview.scene().camera().copyViewFrom(oldCamera);
        appliedSelections = "";
        selectionsPending = false;
        preview.onAlternativeSelected((display, option) -> container.action("material", display.key(), BuilderState.optionKey(display.options().get(option))));
        preview.onCellAlternativeSelected((cell, option) -> container.action("cell_material", BuilderState.cellKey(cell.position()),
            BuilderState.cellValue(Objects.requireNonNull(cell.display()), option, view.settings().orientation())));
    }

    private void applySelections() {
        if (preview == null) return;
        var model = preview.view().model();
        if (model == null) return;
        var tag = container.snapshot();
        String key = tag.getCompoundTag("MaterialSelections").toString() + tag.getCompoundTag("CellMaterialSelections")
            + tag.getCompoundTag("SliceSelections") + model.generation().session() + model.generation().sequence();
        var view = preview.view();
        var slices = BuilderState.previewSlices(tag);
        if (key.equals(appliedSelections) && (!selectionsPending || !view.settings().sliceCounts().equals(slices))) return;
        boolean rebuild = !view.settings().sliceCounts().equals(slices);
        var globals = new Object2IntOpenHashMap<StructureDisplay>();
        for (var line : model.bom()) {
            var display = line.display();
            if (display == null) continue;
            int selected = display.options().indexOf(BuilderState.choice(tag, display));
            if (BuilderState.optionKey(display.options().get(selected)).equals(tag.getCompoundTag("MaterialSelections").getString(display.key())))
                globals.put(display, selected);
        }
        var displays = new Object2ObjectOpenHashMap<BlockPos, StructureDisplay>();
        for (var cell : model.cells()) if (cell.display() != null) displays.put(cell.position(), cell.display());
        if (view.replaceSelections(view.token(), globals, BuilderState.cellChoices(tag, displays, view.settings().orientation()), slices)) {
            appliedSelections = key;
            // Revisit once new occurrences exist, so their persisted single-cell choices are not lost.
            selectionsPending = rebuild;
        }
    }

    @Override
    public void drawBG(int x, int y, int mx, int my, float partial) {
        GuiAtlas.draw(GuiAtlas.texture("gui_build_instrument/build_instrument_base"), x, y, 0, 0, 384, 228, 384, 256);
        refresh();
        if (preview != null) {
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(x + 4, y + 2, 0);
                preview.draw(184, 220, mx - x - 4, my - y - 2, partial, false);
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    @Override
    public void drawFG(int x, int y, int mx, int my) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(191, 8, 0);
            tree.draw(preview == null ? null : preview.view().previews().catalogView(), id, orientation, mx - x - 191, my - y - 8);
        } finally {
            GlStateManager.popMatrix();
        }
        if (!container.snapshot().getBoolean("Bound"))
            fontRenderer.drawStringWithShadow(StructureToolText.Unbound.getLocal(), 10, 207, 0xffffff);
        var tag = container.snapshot();
        var state = BuilderState.state(tag);
        int done = BuilderState.done(tag), total = BuilderState.total(tag);
        int fill = 1 + (int) (158 * Math.clamp(total <= 0 ? 0 : done / (double) total, 0, 1));
        int color = state.paused() ? 0xffeca23c : state == BuilderState.State.DISASSEMBLING ? 0xffd73e42 : 0xff17b86d;
        int tail = state.paused() ? 0xffd9782f : state == BuilderState.State.DISASSEMBLING ? 0xffaa212b : 0xff079b6b;
        drawRect(192, 207, 192 + fill, 216, color);
        drawRect(191 + fill, 207, 192 + fill, 216, tail);
        int v = switch (state) {
            case IDLE -> 0;
            case BUILDING -> 15;
            case PAUSED_BUILDING, PAUSED_DISASSEMBLING -> 30;
            case DISASSEMBLING -> 60;
        };
        int u = taskHover(mx, my) ? 21 : 0;
        GuiAtlas.draw(GuiAtlas.texture("gui_build_instrument/button"), 357, 205, u, v, 20, 14, 80, 80);
        settings(mx - x, my - y);
    }

    private void settings(int mouseX, int mouseY) {
        var tag = container.snapshot();
        boolean enabled = BuilderState.state(tag) == BuilderState.State.IDLE;
        for (int index = 0; index < 4; index++) {
            int x = 193 + index % 2 * 91, y = 154 + index / 2 * 23;
            boolean hover = mouseX >= x && mouseX < x + 89 && mouseY >= y && mouseY < y + 22;
            String variant = enabled && index == pressedSetting ? "pressed" : hover && enabled ? "selected" : "default";
            StructureToolDraw.horizontal("gui_scanner_instrument/output_" + variant,
                x, y, 89, 22, 130, 4, 8, 126, 4);
            var label = settingLabel(index);
            int mode = index == 1 ? BuilderState.meMode(tag) : setting(index).enabled(tag) ? 1 : 0;
            var text = label.getLocal() + " " + (index == 1 ? meLabel(mode).getLocal()
                : (mode == 1 ? StructureToolText.BuilderEnabled : StructureToolText.BuilderDisabled).getLocal());
            GuiLabel.drawCentered(fontRenderer, text, x + 3, y + 3, 83, 16, enabled ? 0xffffff : 0xff969696, false);
        }
    }

    private static BuilderState.Setting setting(int index) {
        return index == 0 ? BuilderState.Setting.REQUIRE_MATERIALS : index == 2 ? BuilderState.Setting.REQUIRE_SPACE : BuilderState.Setting.REPLACE_FLUIDS;
    }

    private static StructureToolText settingLabel(int index) {
        return switch (index) {
            case 0 -> StructureToolText.BuilderRequireMaterials;
            case 1 -> StructureToolText.BuilderMe;
            case 2 -> StructureToolText.BuilderRequireSpace;
            default -> StructureToolText.BuilderReplaceFluids;
        };
    }

    private static StructureToolText meLabel(int mode) {
        return mode == 0 ? StructureToolText.BuilderMeOff : mode == 1 ? StructureToolText.BuilderMeSupply : StructureToolText.BuilderMeCraft;
    }

    private boolean taskHover(int x, int y) {
        return x >= guiLeft + 357 && x < guiLeft + 377 && y >= guiTop + 205 && y < guiTop + 219;
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        super.drawScreen(x, y, partial);
        for (int index = 0; index < 4; index++) {
            int left = guiLeft + 193 + index % 2 * 91, top = guiTop + 154 + index / 2 * 23;
            if (x >= left && x < left + 89 && y >= top && y < top + 22) {
                var tip = switch (index) {
                    case 0 -> StructureToolText.BuilderRequireMaterialsHelp;
                    case 1 -> StructureToolText.BuilderMeHelp;
                    case 2 -> StructureToolText.BuilderRequireSpaceHelp;
                    default -> StructureToolText.BuilderReplaceFluidsHelp;
                };
                drawHoveringText(fontRenderer.listFormattedStringToWidth(tip.getLocal(), 260), x, y);
                return;
            }
        }
        var materialSlot = preview == null ? null : preview.slotAt(x - guiLeft - 4, y - guiTop - 2, 4, 2);
        if (materialSlot != null) {
            boolean rendered = preview.ownsSlot(hoveredSlot);
            hoveredSlot = materialSlot;
            if (!rendered) preview.renderItemTooltip(x, y, x - guiLeft - 4, y - guiTop - 2);
            return;
        }
        if (preview != null && preview.ownsSlot(hoveredSlot)) hoveredSlot = null;
        if (preview != null && preview.renderItemTooltip(x, y, x - guiLeft - 4, y - guiTop - 2)) return;
        List<String> tip = preview == null ? List.of() : preview.tooltip(x - guiLeft - 4, y - guiTop - 2);
        if (tip.isEmpty()) tip = tree.tooltip(x - guiLeft - 191, y - guiTop - 8);
        if (!tip.isEmpty()) drawHoveringText(tip, x, y);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        for (int index = 0; index < 4; index++) {
            int left = guiLeft + 193 + index % 2 * 91, top = guiTop + 154 + index / 2 * 23;
            if (x >= left && x < left + 89 && y >= top && y < top + 22) {
                if (button == 0 && BuilderState.state(container.snapshot()) == BuilderState.State.IDLE) {
                    pressedSetting = index;
                    container.action("builder_setting", index == 1 ? "ME" : setting(index).name(), "");
                }
                return;
            }
        }
        if (preview != null) preview.dismissMaterialsOutside(x - guiLeft - 4, y - guiTop - 2);
        if (button == 0 && taskHover(x, y)) {
            container.action("task");
            return;
        }
        if (tree.press(x - guiLeft - 191, y - guiTop - 8, button)) return;
        if (preview != null && preview.press(x - guiLeft - 4, y - guiTop - 2, button)) return;
        super.mouseClicked(x, y, button);
    }

    @Override
    protected void keyTyped(char character, int code) throws IOException {
        if (code == Keyboard.KEY_ESCAPE && preview != null && preview.dismissMaterials()) {
            hoveredSlot = null;
            return;
        }
        super.keyTyped(character, code);
    }

    @Override
    protected Slot findSlot(int x, int y) {
        var slot = preview == null ? null : preview.slotAt(x - guiLeft - 4, y - guiTop - 2, 4, 2);
        return slot == null ? super.findSlot(x, y) : slot;
    }

    @Override
    protected boolean checkHotbarKeys(int code) {
        return preview != null && preview.ownsSlot(getSlotUnderMouse()) || super.checkHotbarKeys(code);
    }

    @Override
    protected void handleMouseClick(Slot slot, int id, int button, @NotNull ClickType type) {
        if (preview == null || !preview.ownsSlot(slot)) super.handleMouseClick(slot, id, button, type);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        tree.drag(x - guiLeft - 191, y - guiTop - 8, button);
        if (preview != null) preview.drag(x - guiLeft - 4, y - guiTop - 2);
        super.mouseClickMove(x, y, button, elapsed);
    }

    @Override
    protected void mouseReleased(int x, int y, int button) {
        pressedSetting = -1;
        tree.release(x - guiLeft - 191, y - guiTop - 8, button);
        if (preview != null) preview.release(x - guiLeft - 4, y - guiTop - 2, button);
        super.mouseReleased(x, y, button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int delta = Mouse.getEventDWheel(), x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && (tree.wheel(x - guiLeft - 191, y - guiTop - 8, delta) || preview != null && preview.wheel(x - guiLeft - 4, y - guiTop - 2, delta)))
            return;
        super.handleMouseInput();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (preview != null) preview.tick();
    }

    @Override
    public void onGuiClosed() {
        if (preview != null) preview.close();
        super.onGuiClosed();
    }
}
