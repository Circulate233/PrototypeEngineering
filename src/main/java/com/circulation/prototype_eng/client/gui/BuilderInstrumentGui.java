package com.circulation.prototype_eng.client.gui;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import com.circulation.prototype_eng.container.StructureToolContainer;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/** Original PM builder panel: preview at (4,2), clipped tree and task controls. */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class BuilderInstrumentGui extends AEBaseGui<StructureToolContainer> {
    private final BuilderStructureTree tree;
    private StructurePreviewPanel preview;
    private ResourceLocation id;
    private String appliedSelections = "";
    private BlockOrientation orientation;

    public BuilderInstrumentGui(StructureToolContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 228;
        tree = new BuilderStructureTree(container, positions -> {
            if (preview != null) preview.scene().highlighted(positions);
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
            && mc.world.isBlockLoaded(anchor) && mc.world.getTileEntity(anchor) instanceof MachineControllerHost machine ? machine : null;
        var nextOrientation = host == null ? BlockOrientation.NORTH_UP : host.orientation();
        if (next.equals(id) && nextOrientation == orientation) {
            preview.view().anchor(anchor); applySelections(); return;
        }
        var oldCamera = preview == null ? null : preview.scene().camera();
        if (preview != null) preview.close();
        id = next;
        orientation = nextOrientation;
        var machineId = tag.getString("BoundMachineId");
        var machineType = machineId.isBlank() ? null : MachineTypeRegistry.INSTANCE.get(new ResourceLocation(machineId));
        if (machineType != null && !machineType.structureId().equals(id)) machineType = null;
        var view = new StructurePreviewView(StructurePreviews.client(), id, new StructurePreviewSettings(orientation), machineType);
        view.anchor(anchor);
        view.wireframe = false;
        preview = new StructurePreviewPanel(view);
        preview.construction();
        if (oldCamera != null) preview.scene().camera().copyViewFrom(oldCamera);
        appliedSelections = "";
        preview.onAlternativeSelected((display, option) -> container.action("material", display.key(), BuilderState.optionKey(display.options().get(option))));
    }

    private void applySelections() {
        if (preview == null) return;
        var model = preview.view().model();
        if (model == null) return;
        var tag = container.snapshot();
        String key = tag.getCompoundTag("MaterialSelections").toString();
        if (key.equals(appliedSelections)) return;
        var view = preview.view();
        for (var line : model.bom()) {
            var display = line.display();
            if (display == null) continue;
            int selected = display.options().indexOf(BuilderState.choice(tag, display));
            if (view.alternative(display) != selected) {
                view.alternative(view.token(), display, selected);
                return;
            }
        }
        appliedSelections = key;
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
        if (!container.snapshot().getBoolean("Bound")) fontRenderer.drawStringWithShadow(StructureToolText.Unbound.getLocal(), 10, 207, 0xffffff);
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
    }

    private boolean taskHover(int x, int y) {
        return x >= guiLeft + 357 && x < guiLeft + 377 && y >= guiTop + 205 && y < guiTop + 219;
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        super.drawScreen(x, y, partial);
        List<String> tip = preview == null ? List.of() : preview.tooltip(x - guiLeft - 4, y - guiTop - 2);
        if (!tip.isEmpty()) drawHoveringText(tip, x, y);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        if (button == 0 && taskHover(x, y)) {
            container.action("task");
            return;
        }
        if (tree.press(x - guiLeft - 191, y - guiTop - 8, button)) return;
        if (preview != null && preview.press(x - guiLeft - 4, y - guiTop - 2, button)) return;
        super.mouseClicked(x, y, button);
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
        if (preview != null) preview.tick();
    }

    @Override
    public void onGuiClosed() {
        if (preview != null) preview.close();
        super.onGuiClosed();
    }
}
