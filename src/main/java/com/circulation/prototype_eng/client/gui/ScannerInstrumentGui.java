package com.circulation.prototype_eng.client.gui;

import it.unimi.dsi.fastutil.ints.IntList;

import ae2.api.orientation.BlockOrientation;
import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.client.preview.StructurePreviewScene;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import com.circulation.prototype_eng.container.StructureToolContainer;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.ScannerState;
import com.circulation.prototype_eng.structure.tool.StructureToolExport;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Original 384x228 PM selector, using its own textures and AE container transport. */
public final class ScannerInstrumentGui extends AEBaseGui<StructureToolContainer> {
    private final List<Input> fields = new ObjectArrayList<>();
    private final List<Hit> hits = new ObjectArrayList<>();
    private StructurePreviews previews;
    private StructurePreviewScene scene;
    private String previewKey = "";
    private String pressed;
    private boolean sliding;
    private int mouseX, mouseY;
    private Iterator<BlockPos> pendingCells;
    private BlockPos pendingOrigin;
    private StructureTransform pendingRotation;
    private Object2ObjectLinkedOpenHashMap<BlockPos, StructureDisplay> pendingDisplays;

    public ScannerInstrumentGui(StructureToolContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 228;
    }

    @Override
    public void initGui() {
        super.initGui();
        fields.clear();
        field(ScannerState.Field.NAME, 215, 17, 153, StructureToolText.ScannerTooltipLangName);
        field(ScannerState.Field.ID, 215, 31, 153, StructureToolText.ScannerTooltipStructureId);
        field(ScannerState.Field.QUANTITY, 216, 67, 68, StructureToolText.ScannerTooltipExpandedQuantity);
        field(ScannerState.Field.SPACING, 300, 67, 68, StructureToolText.ScannerTooltipExpandedSpacing);
        field(ScannerState.Field.X, 212, 110, 69, StructureToolText.ScannerTooltipExportOriginX);
        field(ScannerState.Field.Y, 212, 124, 69, StructureToolText.ScannerTooltipExportOriginY);
        field(ScannerState.Field.Z, 212, 138, 69, StructureToolText.ScannerTooltipExportOriginZ);
    }

    private void field(ScannerState.Field field, int x, int y, int width, StructureToolText tip) {
        var widget = new GuiTextField(fields.size(), fontRenderer, guiLeft + x + 4, guiTop + y + 2, width - 8, 9);
        widget.setEnableBackgroundDrawing(false);
        widget.setTextColor(0xffffff);
        widget.setMaxStringLength(256);
        fields.add(new Input(field, widget, x, y, width, tip));
    }

    private void commit(Input input) {
        container.action("edit", input.field().name(), input.widget().getText());
        input.widget().setFocused(false);
    }

    private void commitFocused() {
        for (var field : fields) if (field.widget().isFocused()) commit(field);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        fields.forEach(field -> field.widget().updateCursorCounter());
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partial) {
        this.mouseX = mouseX - x;
        this.mouseY = mouseY - y;
        GuiAtlas.draw(GuiAtlas.texture("gui_scanner_instrument/base"), x, y, 0, 0, 384, 228, 384, 256);
        updatePreview();
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            if (scene != null) {
                scene.drawAtCurrentOrigin(10, 12, 171, 203, partial);
                scene.compass(14, 16);
            }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    @Override
    public void drawFG(int x, int y, int mx, int my) {
        hits.clear();
        var tag = container.snapshot();
        for (var input : fields) {
            StructureToolDraw.horizontal("gui_states/input_box/box_expand", input.x(), input.y(), input.width(), 13, 8, 3, 3, 4, 4);
            if (!input.widget().isFocused()) {
                String value = input.field() == ScannerState.Field.NAME || input.field() == ScannerState.Field.ID ? tag.getString(input.field().key) : Integer.toString(tag.getInteger(input.field().key));
                if (!input.widget().getText().equals(value)) input.widget().setText(value);
            }
            hits.add(new Hit("field", input.x(), input.y(), input.width(), 13, input.tip(), null));
        }
        toggle(ScannerState.Field.EXPANDED, 216, 53, StructureToolText.ScannerTooltipExpandedEnabled, false);
        toggle(ScannerState.Field.SUBSTRUCTURE, 215, 88, StructureToolText.ScannerTooltipSubstructure, false);
        toggle(ScannerState.Field.NBT, 263, 88, StructureToolText.ScannerTooltipMatchNbt, false);
        toggle(ScannerState.Field.MIRROR, 306, 88, StructureToolText.ScannerTooltipAllowMirror, false);
        toggle(ScannerState.Field.PREVIEW_MIRROR, 356, 133, StructureToolText.ScannerTooltipPreviewMirror, true);
        button("originReset", "button/reset_i", 200, 152, 39, 13, StructureToolText.ScannerTooltipResetOriginInput);
        button("originSet", "button/set", 242, 152, 39, 13, StructureToolText.ScannerTooltipSetOriginFromInput);
        button("orientationReset", "button/reset_ii", 306, 132, 30, 13, StructureToolText.ScannerTooltipResetPreviewOrientation);
        button("clear", "delete", 197, 191, 21, 22, StructureToolText.ScannerTooltipClear);
        button("reset", "reset", 219, 191, 21, 22, StructureToolText.ScannerTooltipReset);
        button("export", "output", 241, 191, 130, 22, StructureToolText.ScannerTooltipExport);
        facing(EnumFacing.UP, "y_add", 302, 149, StructureToolText.ScannerTooltipPreviewFacingUp);
        facing(EnumFacing.DOWN, "y_sub", 302, 173, StructureToolText.ScannerTooltipPreviewFacingDown);
        facing(EnumFacing.EAST, "x_add", 290, 161, StructureToolText.ScannerTooltipPreviewFacingEast);
        facing(EnumFacing.WEST, "x_sub", 314, 161, StructureToolText.ScannerTooltipPreviewFacingWest);
        facing(EnumFacing.NORTH, "z_add", 302, 161, StructureToolText.ScannerTooltipPreviewFacingNorth);
        facing(EnumFacing.SOUTH, "z_sub", 290, 173, StructureToolText.ScannerTooltipPreviewFacingSouth);
        rotation(0, "y_add", 345, 149, StructureToolText.ScannerTooltipPreviewRot0);
        rotation(1, "y_sub", 345, 173, StructureToolText.ScannerTooltipPreviewRot1);
        rotation(2, "x_sub", 333, 161, StructureToolText.ScannerTooltipPreviewRot2);
        rotation(3, "x_add", 357, 161, StructureToolText.ScannerTooltipPreviewRot3);
        StructureToolDraw.horizontal("gui_states/slider/m/normal/lr_base_expand", 238, 53, 131, 13, 17, 7, 7, 8, 9);
        int handle = 239 + (int) (118 * Math.clamp((tag.getInteger(ScannerState.Field.QUANTITY.key) - 1) / 255d, 0, 1));
        StructureToolDraw.image("gui_states/slider/m/lr_" + (sliding ? "pressed" : inside(238, 53, 131, 13) ? "selected" : "default"), handle, 54, 11, 11);
        hits.add(new Hit("slider", 238, 53, 131, 13, StructureToolText.ScannerTooltipExpandedQuantity, null));
        hits.add(new Hit("preview", 10, 12, 171, 203, StructureToolText.ScannerTooltipPreview, null));
        var box = ScannerState.box(tag);
        String size = box == null ? StructureToolText.ScannerSizePreviewEmpty.getLocal() : StructureToolText.ScannerSizePreviewValue.getLocal(box.sizeX(), box.sizeY(), box.sizeZ());
        GuiLabel.draw(fontRenderer, size, 214, 175, 64, 8, 0xffffff, false);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(-x, -y, 0);
            fields.forEach(field -> field.widget().drawTextBox());
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private boolean inside(int x, int y, int w, int h) {
        return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
    }

    private void button(String id, String path, int x, int y, int w, int h, StructureToolText tip) {
        String variant = id.equals(pressed) ? "pressed" : inside(x, y, w, h) ? "selected" : "default";
        StructureToolDraw.image("gui_scanner_instrument/" + path + "_" + variant, x, y, w, h);
        hits.add(new Hit(id, x, y, w, h, tip, () -> container.action(id)));
    }

    private void toggle(ScannerState.Field field, int x, int y, StructureToolText tip, boolean small) {
        boolean on = container.snapshot().getBoolean(field.key);
        int w = small ? 11 : 20, h = small ? 11 : 13;
        StructureToolDraw.image("gui_states/" + (small ? "choose_switch/normal/" : "switch/s_normal/") + (on ? "on" : "off") + (inside(x, y, w, h) ? "_selected" : ""), x, y, w, h);
        hits.add(new Hit(field.name(), x, y, w, h, tip, () -> container.action("edit", field.name(), Boolean.toString(!on))));
    }

    private void facing(EnumFacing facing, String path, int x, int y, StructureToolText tip) {
        direction("facing", facing.getIndex(), "preview/" + path, x, y, tip, container.snapshot().getInteger("previewFacing"));
    }

    private void rotation(int value, String path, int x, int y, StructureToolText tip) {
        direction("rotation", value, "preview/rotate/" + path, x, y, tip, container.snapshot().getInteger("previewRot"));
    }

    private void direction(String action, int value, String path, int x, int y, StructureToolText tip, int selected) {
        StructureToolDraw.image("gui_scanner_instrument/" + path + "/" + (value == selected || inside(x, y, 11, 11) ? "selected" : "default"), x, y, 11, 11);
        hits.add(new Hit(action + value, x, y, 11, 11, tip, () -> container.action(action, "", Integer.toString(value))));
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        super.drawScreen(x, y, partial);
        for (var hit : hits)
            if (hit.contains(x - guiLeft, y - guiTop)) {
                drawHoveringText(List.of(hit.tip().getLocal()), x, y);
                break;
            }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        commitFocused();
        for (var input : fields)
            if (x >= guiLeft + input.x() && x < guiLeft + input.x() + input.width() && y >= guiTop + input.y() && y < guiTop + input.y() + 13) {
                input.widget().mouseClicked(x, y, button);
                input.widget().setFocused(true);
                return;
            }
        if (scene != null && scene.press(x, y, button)) return;
        if (button == 0) for (var hit : hits)
            if (hit.contains(x - guiLeft, y - guiTop)) {
                pressed = hit.id();
                if (hit.id().equals("slider")) {
                    sliding = true;
                    slider(x);
                } else if (hit.action() != null) hit.action().run();
                return;
            }
        super.mouseClicked(x, y, button);
    }

    private void slider(int x) {
        container.action("edit", ScannerState.Field.QUANTITY.name(), Integer.toString(1 + (int) Math.round(255 * Math.clamp((x - guiLeft - 239 - 5) / 118d, 0, 1))));
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (sliding) {
            slider(x);
            return;
        }
        if (scene != null && scene.drag(x, y)) return;
        super.mouseClickMove(x, y, button, elapsed);
    }

    @Override
    protected void mouseReleased(int x, int y, int button) {
        pressed = null;
        sliding = false;
        if (scene != null) scene.releaseWithoutPicking(button);
        super.mouseReleased(x, y, button);
    }

    @Override
    protected void keyTyped(char ch, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            commitFocused();
            return;
        }
        for (var field : fields)
            if (field.widget().isFocused()) {
                if (key == Keyboard.KEY_ESCAPE) commit(field);
                else field.widget().textboxKeyTyped(ch, key);
                return;
            }
        super.keyTyped(ch, key);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int delta = Mouse.getEventDWheel(), x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && scene != null && scene.wheel(x, y, delta)) return;
        super.handleMouseInput();
    }

    private void updatePreview() {
        var tag = container.snapshot();
        String next = ScannerState.position(tag, "origin") + "/" + ScannerState.position(tag, "corner") + "/" + ScannerState.origin(tag)
            + "/" + tag.getInteger("previewFacing") + "/" + tag.getInteger("previewRot")
            + "/" + tag.getBoolean(ScannerState.Field.PREVIEW_MIRROR.key) + "/" + tag.getInteger("dimension");
        if (!previewKey.equals(next)) {
            previewKey = next;
            var box = ScannerState.box(tag);
            var origin = ScannerState.origin(tag);
            if (box == null || origin == null || mc.world == null || tag.getInteger("dimension") != mc.world.provider.getDimension()) {
                pendingCells = null;
                pendingDisplays = null;
                closePreview();
                return;
            }
            pendingCells = box.previewCells();
            pendingOrigin = origin;
            pendingRotation = ScannerState.worldToTemplate(tag);
            pendingDisplays = new Object2ObjectLinkedOpenHashMap<>();
        }
        if (pendingCells == null || mc.world == null) return;
        for (int visited = 0; visited < 4096 && pendingCells.hasNext(); visited++) {
            var pos = pendingCells.next();
            if (!mc.world.isBlockLoaded(pos) || mc.world.isAirBlock(pos)) continue;
            var cell = StructureToolExport.cell(mc.world, pos, pendingOrigin, pendingRotation, false);
            pendingDisplays.put(cell.position(), new StructureDisplay(cell.block() + "@" + cell.meta(), List.of(new StructureDisplay.BlockOption(cell.block(), cell.meta(), Map.of())), null));
        }
        if (pendingCells.hasNext()) return;
        pendingCells = null;
        var displays = pendingDisplays;
        pendingDisplays = null;
        var previousCamera = scene == null ? null : scene.camera();
        closePreview();
        var id = new ResourceLocation("prototype_eng", "scanner_preview");
        var north = BlockOrientation.get(EnumFacing.NORTH, EnumFacing.UP);
        var node = new StructureCatalogView.Node(id, north, false, StructurePreviewProvider.Kind.TEMPLATE, BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, List.copyOf(displays.keySet()), IntList.of());
        var catalog = ClientStructureCatalog.local(new StructureCatalogView(UUID.randomUUID(), 0, List.of(node), Map.of(id, new StructureCatalogView.Entry(0, null, displays))));
        previews = new StructurePreviews(catalog);
        var view = new StructurePreviewView(previews, id, new StructurePreviewSettings(north));
        view.wireframe = false;
        scene = new StructurePreviewScene(view);
        if (previousCamera != null) scene.camera().copyViewFrom(previousCamera);
    }

    private void closePreview() {
        if (scene != null) scene.close();
        if (previews != null) previews.close();
        scene = null;
        previews = null;
    }

    @Override
    public void onGuiClosed() {
        commitFocused();
        pendingCells = null;
        pendingDisplays = null;
        closePreview();
        super.onGuiClosed();
    }

    private record Input(ScannerState.Field field, GuiTextField widget, int x, int y, int width,
                         StructureToolText tip) {
    }

    private record Hit(String id, int x, int y, int width, int height, StructureToolText tip, Runnable action) {
        boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }
}
