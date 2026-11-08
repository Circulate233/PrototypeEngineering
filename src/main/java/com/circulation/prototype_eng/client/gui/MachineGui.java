package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.style.BackgroundGenerator;
import ae2.client.gui.widgets.Scrollbar;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.container.MachineContainer;
import com.circulation.prototype_eng.container.MachineGuiSnapshot;
import com.circulation.prototype_eng.localization.MachineGuiText;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewScreens;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import net.minecraft.client.gui.GuiButton;

/** PM's scrollable seven-row progress view, directly composed on AE2's GUI and scrollbar. */
public final class MachineGui extends AEBaseGui<MachineContainer> {
    private static final ResourceLocation PROGRESS = new ResourceLocation(Tags.MOD_ID, "textures/gui/gui_controller_states.png");
    private static final int LIST_X = 8;
    private static final int LIST_Y = 20;
    private static final int ROW_HEIGHT = 33;
    private final Scrollbar scrollbar = new Scrollbar();
    private int visibleRows;
    private int requestedOffset = -1;
    private int requestedRows = -1;

    public MachineGui(MachineContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 256;
        scrollbar.setCaptureMouseWheel(false);
    }

    @Override public void initGui() {
        xSize = Math.min(384, width - 8);
        ySize = Math.min(256, height - 8);
        super.initGui();
        buttonList.add(new GuiButton(80, guiLeft + xSize - 54, guiTop + 3, 50, 16, StructurePreviewText.Open.getLocal()));
        visibleRows = Math.clamp((ySize - LIST_Y - 5) / ROW_HEIGHT, 1, MachineGuiSnapshot.MAX_ROWS);
        scrollbar.setPosition(new Point(100, LIST_Y));
        scrollbar.setHeight(visibleRows * ROW_HEIGHT);
        for (var slot : container.inventorySlots) {
            int index = slot.getSlotIndex();
            slot.xPos = xSize - 170 + (index % 9) * 18;
            slot.yPos = index < 9 ? ySize - 24 : ySize - 82 + ((index - 9) / 9) * 18;
        }
        updateView();
    }

    private void updateView() {
        scrollbar.setRange(0, Math.max(0, container.snapshot.totalSlots() - visibleRows), 1);
        int offset = scrollbar.getCurrentScroll();
        if (offset != requestedOffset || visibleRows != requestedRows) {
            requestedOffset = offset;
            requestedRows = visibleRows;
            container.requestView(offset, visibleRows);
        }
    }

    @Override public void updateScreen() {
        super.updateScreen();
        scrollbar.tick();
        updateView();
    }

    @Override public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, x, y);
        GlStateManager.color(1, 1, 1, 1);
        bindTexture(PROGRESS);
        int count = Math.min(visibleRows, container.snapshot.entries().size());
        for (int row = 0; row < count; row++) {
            int top = y + LIST_Y + row * ROW_HEIGHT;
            drawTexturedModalRect(x + LIST_X, top, 0, 0, 89, ROW_HEIGHT);
            int filled = container.snapshot.entries().get(row).percent() * 89 / 100;
            if (filled > 0) { drawTexturedModalRect(x + LIST_X, top, 0, 34, filled, ROW_HEIGHT); }
        }
        for (var slot : container.inventorySlots) {
            int left = x + slot.xPos - 1;
            int top = y + slot.yPos - 1;
            drawRect(left, top, left + 18, top + 18, 0xff373737);
            drawRect(left + 1, top + 1, left + 18, top + 18, 0xffffffff);
            drawRect(left + 1, top + 1, left + 17, top + 17, 0xff8b8b8b);
        }
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override public void drawFG(int x, int y, int mouseX, int mouseY) {
        var snapshot = container.snapshot;
        fontRenderer.drawString(MachineGuiText.WorkSlots.getLocal(), LIST_X, 6, 0x404040);
        fontRenderer.drawString(fontRenderer.trimStringToWidth(Objects.requireNonNull(container.getGuiTitle()).getUnformattedText(), Math.max(0, xSize - 184)), 124, 6, 0x404040);
        var status = !snapshot.active() ? MachineGuiText.Unavailable : snapshot.formed() ? MachineGuiText.Formed : MachineGuiText.Unformed;
        fontRenderer.drawString(status.getLocal(), 124, 24, 0x404040);
        if (snapshot.totalSlots() == 0) { fontRenderer.drawString(MachineGuiText.NoWorkSlots.getLocal(), 124, 38, 0x404040); }
        for (int row = 0; row < Math.min(visibleRows, snapshot.entries().size()); row++) {
            var entry = snapshot.entries().get(row);
            int top = LIST_Y + row * ROW_HEIGHT;
            String title = MachineGuiText.Slot.getLocal(snapshot.offset() + row + 1);
            fontRenderer.drawString(title, LIST_X + 4, top + 4, 0xffffff);
            String progress = MachineGuiText.Progress.getLocal(entry.percent());
            if (!entry.recipe().isEmpty()) { fontRenderer.drawString(progress, LIST_X + 85 - fontRenderer.getStringWidth(progress), top + 4, 0xffffff); }
            int color = entry.status() == WorkSlotStatus.FAILED || entry.status() == WorkSlotStatus.BLOCKED ? 0xff7777 : 0xffffff;
            fontRenderer.drawString(fontRenderer.trimStringToWidth(MachineGuiText.reason(entry), 81), LIST_X + 4, top + 16, color);
        }
        scrollbar.drawForegroundLayer(scrollbar.getBounds(), new Point(mouseX - guiLeft, mouseY - guiTop));
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (int row = 0; row < Math.min(visibleRows, container.snapshot.entries().size()); row++) {
            if (isPointInRegion(LIST_X, LIST_Y + row * ROW_HEIGHT, 89, ROW_HEIGHT, mouseX, mouseY)) {
                var entry = container.snapshot.entries().get(row);
                drawHoveringText(List.of(entry.name(), MachineGuiText.Recipe.getLocal(entry.recipe()), MachineGuiText.reason(entry)), mouseX, mouseY);
            }
        }
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (scrollbar.getBounds().contains(mouseX - guiLeft, mouseY - guiTop)
                && scrollbar.onMouseDown(new Point(mouseX - guiLeft, mouseY - guiTop), button)) { updateView(); return; }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 80 && container.getTarget() instanceof MachineControllerHost host) {
            StructurePreviewScreens.open(this, host.type(), host.orientation());
            return;
        }
        super.actionPerformed(button);
    }
    @Override protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (scrollbar.onMouseDrag(new Point(mouseX - guiLeft, mouseY - guiTop), button)) { updateView(); return; }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }
    @Override protected void mouseReleased(int mouseX, int mouseY, int button) {
        scrollbar.onMouseUp(new Point(mouseX - guiLeft, mouseY - guiTop), button);
        super.mouseReleased(mouseX, mouseY, button);
    }
    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (wheel != 0 && isPointInRegion(LIST_X, LIST_Y, 106, visibleRows * ROW_HEIGHT, mouseX, mouseY)) {
            scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - Integer.signum(wheel));
            updateView();
        }
    }
}
