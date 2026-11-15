package com.circulation.prototype_eng.client.gui;

import ae2.api.orientation.BlockOrientation;
import ae2.client.gui.AEBaseGui;
import ae2.client.Point;
import ae2.client.gui.Icon;
import ae2.client.gui.widgets.TabButton;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.client.preview.GuiPreviewNative;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import com.circulation.prototype_eng.container.ContainerStructureTool;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import com.circulation.prototype_eng.structure.tool.ConstructionTarget;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/**
 * Original PM builder panel: preview at (4,2), clipped tree and task controls.
 */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class GuiBuilderInstrument extends AEBaseGui<ContainerStructureTool> implements GuiPreviewNative {
    private static final int SETTINGS_LEFT = 196;
    private static final int SETTINGS_TOP = 146;
    private static final int SETTINGS_COLUMN = 92;
    private static final int SETTINGS_ROW = 18;
    private final BuilderStructureTree tree;
    private final TabButton upgrades;
    private StructurePreviewPanel preview;
    private ResourceLocation id;
    private NBTTagCompound appliedSelections;
    private StructureCatalogView appliedGeneration;
    private boolean selectionsPending;
    private BlockOrientation orientation;

    public GuiBuilderInstrument(ContainerStructureTool container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 228;
        upgrades = new TabButton(Icon.WIRELESS_SETTINGS_PAGE, StructureToolText.BuilderUpgrades.text(),
            () -> container.action("upgrades"));
        widgets.add("upgrades", upgrades, new Point(360, -5));
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
            if (path != null && view.slices(view.token(), path, count))
                container.action("slice", key, Integer.toString(count));
        });
    }

    private static boolean settingHover(int index, int mouseX, int mouseY) {
        int left = SETTINGS_LEFT + index % 2 * SETTINGS_COLUMN + 56;
        int top = SETTINGS_TOP + index / 2 * SETTINGS_ROW + 3;
        return mouseX >= left && mouseX < left + 20 && mouseY >= top && mouseY < top + 13;
    }

    private static boolean settingRowHover(int index, int mouseX, int mouseY) {
        int left = SETTINGS_LEFT + index % 2 * SETTINGS_COLUMN;
        int top = SETTINGS_TOP + index / 2 * SETTINGS_ROW;
        return mouseX >= left && mouseX < left + 76 && mouseY >= top && mouseY < top + SETTINGS_ROW;
    }

    private static BuilderState.Setting setting(int index) {
        return switch (index) {
            case 0 -> BuilderState.Setting.REQUIRE_MATERIALS;
            case 1 -> BuilderState.Setting.WAIT_MATERIALS;
            case 2 -> BuilderState.Setting.REQUIRE_SPACE;
            default -> BuilderState.Setting.REPLACE_FLUIDS;
        };
    }

    private static StructureToolText settingLabel(int index) {
        return switch (index) {
            case 0 -> StructureToolText.BuilderRequireMaterials;
            case 1 -> StructureToolText.BuilderWaitMaterials;
            case 2 -> StructureToolText.BuilderRequireSpace;
            case 3 -> StructureToolText.BuilderReplaceFluids;
            case 4 -> StructureToolText.BuilderMe;
            default -> StructureToolText.BuilderMeCraft;
        };
    }

    @Override
    public void prototype_eng$renderItemTooltip(ItemStack stack, int x, int y) {
        renderToolTip(stack, x, y);
    }

    @SuppressWarnings("DataFlowIssue")
    // Cell alternative callbacks are invoked only for cells with display alternatives.
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
        appliedSelections = null;
        appliedGeneration = null;
        selectionsPending = false;
        preview.onAlternativeSelected((display, option) -> container.action("material", display.key(), BuilderState.optionKey(display.options().get(option))));
        preview.onCellAlternativeSelected((cell, option) -> container.action("cell_material", BuilderState.cellKey(cell.position()),
            BuilderState.cellValue(cell.display(), option, view.settings().orientation())));
    }

    private void applySelections() {
        if (preview == null) return;
        var model = preview.view().model();
        if (model == null) return;
        var tag = container.snapshot();
        var view = preview.view();
        boolean unchanged = appliedGeneration == model.generation() && BuilderStructureTree.sameSelectionData(appliedSelections, tag);
        if (unchanged && !selectionsPending) {
            appliedSelections = tag;
            return;
        }
        var slices = BuilderState.previewSlices(tag);
        if (unchanged && !view.settings().sliceCounts().equals(slices)) return;
        boolean rebuild = !view.settings().sliceCounts().equals(slices);
        var globals = new Object2IntOpenHashMap<StructureDisplay>();
        var elements3 = model.bom();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var line = elements3.get(index3);
            var display = line.display();
            if (display == null) continue;
            int selected = display.options().indexOf(BuilderState.choice(tag, display));
            if (BuilderState.optionKey(display.options().get(selected)).equals(tag.getCompoundTag("MaterialSelections").getString(display.key())))
                globals.put(display, selected);
        }
        var displays = new BlockPos2ObjectMap<StructureDisplay>();
        var elements = model.cells();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var cell = elements.get(index1);
            if (cell.display() != null) displays.put(cell.position(), cell.display());
        }
        if (view.replaceSelections(view.token(), globals, BuilderState.cellChoices(tag, displays, view.settings().orientation()), slices)) {
            appliedSelections = tag;
            appliedGeneration = model.generation();
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
        for (int index = 0; index < 6; index++) {
            int left = SETTINGS_LEFT + index % 2 * SETTINGS_COLUMN;
            int top = SETTINGS_TOP + index / 2 * SETTINGS_ROW;
            boolean enabled = settingAvailable(index);
            var label = fontRenderer.listFormattedStringToWidth(settingLabel(index).getLocal(), 54);
            int textY = top + Math.max(0, (SETTINGS_ROW - label.size() * fontRenderer.FONT_HEIGHT) / 2);
            for (int index2 = 0; index2 < label.size(); index2++) {
                var line = label.get(index2);
                fontRenderer.drawString(line, left, textY, enabled ? 0xffffff : 0xff969696);
                textY += fontRenderer.FONT_HEIGHT;
            }
            // The exact switch used by the structure selector: no custom sprite or stretched frame.
            StructureToolDraw.image("gui_states/switch/s_normal/" + (settingOn(index) ? "on" : "off")
                + (enabled && settingHover(index, mouseX, mouseY) ? "_selected" : ""), left + 56, top + 3, 20, 13);
        }
    }

    private boolean settingAvailable(int index) {
        var tag = container.snapshot();
        return BuilderState.state(tag) == BuilderState.State.IDLE
            && (index == 5 ? BuilderState.meMode(tag) > 0 : index == 4 || setting(index).available(tag));
    }

    private boolean settingOn(int index) {
        var tag = container.snapshot();
        return index == 4 ? BuilderState.meMode(tag) > 0 : index == 5 ? BuilderState.meMode(tag) == 2 : setting(index).enabled(tag);
    }

    private boolean taskHover(int x, int y) {
        return x >= guiLeft + 357 && x < guiLeft + 377 && y >= guiTop + 205 && y < guiTop + 219;
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        super.drawScreen(x, y, partial);
        for (int index = 0; index < 6; index++) {
            if (settingRowHover(index, x - guiLeft, y - guiTop)) {
                var tip = switch (index) {
                    case 0 -> StructureToolText.BuilderRequireMaterialsHelp;
                    case 1 -> StructureToolText.BuilderWaitMaterialsHelp;
                    case 2 -> StructureToolText.BuilderRequireSpaceHelp;
                    case 3 -> StructureToolText.BuilderReplaceFluidsHelp;
                    case 4 -> StructureToolText.BuilderMeHelp;
                    default -> StructureToolText.BuilderMeCraftHelp;
                };
                String status = StructureToolText.BuilderSettingState.getLocal(
                    (settingOn(index) ? StructureToolText.BuilderEnabled : StructureToolText.BuilderDisabled).getLocal());
                String text = settingLabel(index).getLocal() + "\n" + status + "\n" + tip.getLocal().replace("\\n", "\n");
                drawHoveringText(fontRenderer.listFormattedStringToWidth(text, 160), x, y);
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
        if (x >= upgrades.x && x < upgrades.x + upgrades.width && y >= upgrades.y && y < upgrades.y + upgrades.height) {
            super.mouseClicked(x, y, button);
            return;
        }
        for (int index = 0; index < 6; index++) {
            if (settingHover(index, x - guiLeft, y - guiTop)) {
                if (button == 0 && settingAvailable(index)) {
                    String key = index == 4 ? "ME_USE" : index == 5 ? "ME_CRAFT" : setting(index).name();
                    container.action("builder_setting", key, "");
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
        upgrades.enabled = BuilderState.state(container.snapshot()) == BuilderState.State.IDLE;
        if (preview != null) preview.tick();
    }

    @Override
    public void onGuiClosed() {
        if (preview != null) preview.close();
        super.onGuiClosed();
    }
}
