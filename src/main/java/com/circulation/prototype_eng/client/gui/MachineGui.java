package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.container.MachineContainer;
import com.circulation.prototype_eng.container.MachineGuiSnapshot;
import com.circulation.prototype_eng.localization.MachineGuiText;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.client.gui.GuiButton;
import ae2.container.slot.AppEngSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.init.SoundEvents;
import org.jetbrains.annotations.NotNull;
/** PM DefaultMachineUI, retaining its original two pages, coordinates and scroll handle. */
public final class MachineGui extends AEBaseGui<MachineContainer> {
    private static final ResourceLocation MAIN = GuiAtlas.texture("gui_controller_a");
    private static final ResourceLocation EXTENSION = GuiAtlas.texture("gui_controller_b");
    private static final ResourceLocation PROGRESS = GuiAtlas.texture("gui_controller_states");
    private static final int LIST_X = 27, LIST_Y = 8, ROW_HEIGHT = 33, LIST_HEIGHT = 238;
    private int page;
    private int scrollIndex;
    private int requestedOffset = -1;
    private boolean dragging;
    private int dragOffset;

    public MachineGui(MachineContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 256;
    }

    @Override public void initGui() {
        super.initGui();
        addButton(new PageTab(0, guiLeft, guiTop));
        addButton(new PageTab(1, guiLeft, guiTop + 22));
        updatePage();
        updateView();
    }

    private int maxScroll() { return Math.max(0, container.snapshot.totalSlots() - MachineGuiSnapshot.MAX_ROWS); }

    private void updateView() {
        scrollIndex = Math.clamp(scrollIndex, 0, maxScroll());
        if (scrollIndex != requestedOffset) {
            requestedOffset = scrollIndex;
            container.requestView(scrollIndex, MachineGuiSnapshot.MAX_ROWS);
        }
    }

    private void updatePage() {
        for (var slot : container.inventorySlots) if (slot instanceof AppEngSlot aeSlot) aeSlot.setActive(page == 0);
    }

    @Override public void updateScreen() { super.updateScreen(); updateView(); }

    @Override public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(page == 0 ? MAIN : EXTENSION, x, y, 0, 0, 384, 256, 384, 256);
        drawRect(x + 140, y + 9, x + 376, y + (page == 0 ? 165 : 248), 0x4c424242);
        if (page != 0) return;
        for (int row = 0; row < container.snapshot.entries().size(); row++) {
            int top = y + LIST_Y + row * ROW_HEIGHT;
            GuiAtlas.draw(PROGRESS, x + LIST_X, top, 0, 0, 89, ROW_HEIGHT, 256, 256);
            int filled = container.snapshot.entries().get(row).percent() * 89 / 100;
            if (filled > 0) GuiAtlas.draw(PROGRESS, x + LIST_X, top, 0, 34, filled, ROW_HEIGHT, 256, 256);
        }
    }

    @Override public void drawFG(int x, int y, int mouseX, int mouseY) {
        fontRenderer.drawStringWithShadow(Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 144, 13, 0xffffff);
        if (page == 1) {
            fontRenderer.drawString(MachineGuiText.ExtensionPage.getLocal(), 144, 33, 0xe0e0e0);
            fontRenderer.drawSplitString(MachineGuiText.ExtensionDescription.getLocal(), 144, 47, 228, 0x9e9e9e);
            return;
        }
        fontRenderer.drawString(MachineGuiText.StatusIdle.getLocal(), 144, 27, 0xe0e0e0);
        var snapshot = container.snapshot;
        for (int row = 0; row < snapshot.entries().size(); row++) {
            var entry = snapshot.entries().get(row);
            int top = LIST_Y + row * ROW_HEIGHT;
            boolean idle = entry.recipe().isEmpty();
            boolean error = entry.status() == WorkSlotStatus.FAILED || entry.status() == WorkSlotStatus.BLOCKED;
            fontRenderer.drawString("#" + (snapshot.offset() + row + 1), LIST_X + 4, top + 4, 0xffffff);
            if (!idle) {
                String percent = entry.percent() + "%";
                fontRenderer.drawString(percent, LIST_X + 85 - fontRenderer.getStringWidth(percent), top + 4, error ? 0xff5555 : 0x55ff55);
            }
            String status = idle ? MachineGuiText.Idle.getLocal() : MachineGuiText.reason(entry);
            fontRenderer.drawString(fontRenderer.trimStringToWidth(status, 81), LIST_X + 4, top + 16,
                idle ? 0xaaaaaa : error ? 0xff5555 : 0xffffff);
        }
        if (maxScroll() > 0) {
            int handleY = handleY();
            boolean hover = isPointInRegion(LIST_X + 47, handleY, 38, 15, mouseX, mouseY);
            mc.getTextureManager().bindTexture(GuiAtlas.STATES);
            GlStateManager.color(1, 1, 1, 1);
            Gui.drawScaledCustomSizeModalRect(LIST_X + 47, handleY, dragging ? 172 : hover ? 159 : 146, 1,
                13, 15, 38, 15, 256, 256);
        }
    }

    private int handleY() { return LIST_Y + (int) Math.round(223.0 * scrollIndex / Math.max(1, maxScroll())); }

    @Override protected void actionPerformed(@NotNull GuiButton button) throws IOException {
        if (button instanceof PageTab) {
            page = button.id;
            dragging = false;
            updatePage();
            container.selectPage(page);
        } else super.actionPerformed(button);
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (page == 0 && button == 0 && maxScroll() > 0 && isPointInRegion(LIST_X + 47, handleY(), 38, 15, mouseX, mouseY)) {
            dragging = true;
            dragOffset = mouseY - guiTop - handleY();
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1));
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (dragging && button == 0) {
            scrollIndex = (int) Math.round(Math.clamp(mouseY - guiTop - LIST_Y - dragOffset, 0, 223) / 223.0 * maxScroll());
            updateView();
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override protected void mouseReleased(int mouseX, int mouseY, int button) {
        if (button == 0) dragging = false;
        super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (page == 0 && wheel != 0 && isPointInRegion(LIST_X, LIST_Y, 89, LIST_HEIGHT, mouseX, mouseY)) {
            scrollIndex -= Integer.signum(wheel);
            updateView();
        }
    }

    private final class PageTab extends GuiButton {
        private PageTab(int id, int x, int y) { super(id, x, y, 21, 22, ""); }

        @Override public void drawButton(@NotNull Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            GuiAtlas.state(x, y, 185 + (page == id ? 48 : hovered ? 24 : 0), id == 0 ? 182 : 207, 21, 22);
        }
    }
}
