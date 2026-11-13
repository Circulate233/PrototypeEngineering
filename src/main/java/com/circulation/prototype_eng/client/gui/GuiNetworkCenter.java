package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.container.ContainerNetworkCenter;
import com.circulation.prototype_eng.localization.NetworkCenterGuiText;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Pure text display, with one to five rows and scrolling only after the maximum height.
 */
@SideOnly(Side.CLIENT)
public class GuiNetworkCenter extends AEBaseGui<ContainerNetworkCenter> {
    public static final int MIN_HEIGHT = 162;
    public static final int MAX_HEIGHT = 242;
    public static final int BASE_HEIGHT = 142;
    public static final int ROW_HEIGHT = 20;
    private static final int MAX_ROWS = (MAX_HEIGHT - BASE_HEIGHT) / ROW_HEIGHT;
    private static final int LIST_X = 22, LIST_Y = 116, SCROLL_X = 360, SCROLL_WIDTH = 6;
    private int visibleRows = 1;
    private int scrollIndex;
    private boolean dragging;
    private int dragOffset;

    public GuiNetworkCenter(ContainerNetworkCenter container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = MIN_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, container.resources.rows().size() - MAX_ROWS);
    }

    private int listWidth() {
        return maxScroll() > 0 ? 330 : 340;
    }

    private void updateView() {
        visibleRows = Math.clamp(container.resources.rows().size(), 1, MAX_ROWS);
        ySize = BASE_HEIGHT + visibleRows * ROW_HEIGHT;
        guiTop = (height - ySize) / 2;
        scrollIndex = Math.clamp(scrollIndex, 0, maxScroll());
        if (maxScroll() == 0) dragging = false;
    }

    @Override
    public void initGui() {
        super.initGui();
        container.resources.invalidateText();
        updateView();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        updateView();
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(x, y, x + xSize, y + ySize, 0xffb8bbc5);
        Gui.drawRect(x, y, x + xSize - 2, y + 2, 0xfff1f1f4);
        Gui.drawRect(x, y, x + 2, y + ySize - 2, 0xfff1f1f4);
        Gui.drawRect(x + xSize - 2, y + 2, x + xSize, y + ySize, 0xff727786);
        Gui.drawRect(x + 2, y + ySize - 2, x + xSize, y + ySize, 0xff727786);
        Gui.drawRect(x + 8, y + 26, x + 376, y + ySize - 8, 0xff707583);
        Gui.drawRect(x + 10, y + 28, x + 376, y + ySize - 8, 0xffe7e8ed);
        Gui.drawRect(x + 10, y + 28, x + 374, y + ySize - 10, 0xff171c21);
        Gui.drawRect(x + 22, y + 92, x + 362, y + 93, 0xff424b54);
        Gui.drawRect(x + 150, y + 58, x + 362, y + 64, 0xff606b74);
        Gui.drawRect(x + 151, y + 59, x + 361, y + 63, 0xff222a30);
        double fraction = container.concurrency == 0 ? 0 : Math.min(1, (double) container.concurrencyUsed / container.concurrency);
        Gui.drawRect(x + 151, y + 59, x + 151 + (int) (210 * fraction), y + 63, 0xff86adae);
        for (int row = 0; row < Math.min(visibleRows, container.resources.rows().size()); row++) {
            int top = y + LIST_Y + row * ROW_HEIGHT;
            Gui.drawRect(x + LIST_X, top + ROW_HEIGHT - 1, x + LIST_X + listWidth(), top + ROW_HEIGHT, 0xff303b43);
        }
        if (maxScroll() > 0) {
            int length = visibleRows * ROW_HEIGHT;
            Gui.drawRect(x + SCROLL_X, y + LIST_Y, x + SCROLL_X + SCROLL_WIDTH, y + LIST_Y + length, 0xff2c343c);
            int handle = handleY();
            Gui.drawRect(x + SCROLL_X, y + handle, x + SCROLL_X + SCROLL_WIDTH, y + handle + handleHeight(), dragging ? 0xffb6c7ce : 0xff85929c);
        }
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        fontRenderer.drawString(Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 14, 9, 0x40454e);
        GlStateManager.pushMatrix();
        GlStateManager.translate(24, 42, 0);
        GlStateManager.scale(3, 3, 1);
        fontRenderer.drawString(container.networkLevel == 0 ? "—" : (container.networkLevel < 10 ? "0" : "") + container.networkLevel, 0, 0, 0xe1edf1);
        GlStateManager.popMatrix();
        fontRenderer.drawString(NetworkCenterGuiText.Level.getLocal(), 69, 42, 0x87969f);
        var status = container.state == NetworkWorkState.ACTIVE_CENTER ? NetworkCenterGuiText.Active
            : container.state == NetworkWorkState.SAME_TYPE_CONFLICT ? NetworkCenterGuiText.Standby : NetworkCenterGuiText.Inactive;
        fontRenderer.drawString(status.getLocal(), 69, 57, container.state == NetworkWorkState.ACTIVE_CENTER ? 0x9dccb4 : 0xc3ac87);
        fontRenderer.drawString(NetworkCenterGuiText.Concurrency.getLocal(), 150, 40, 0xd5e1e6);
        String concurrency = container.concurrencyUsed + " / " + container.concurrency;
        fontRenderer.drawString(concurrency, 362 - fontRenderer.getStringWidth(concurrency), 40, 0xe1edf1);
        String available = NetworkCenterGuiText.Available.getLocal(Math.max(0, container.concurrency - container.concurrencyUsed));
        fontRenderer.drawString(available, 362 - fontRenderer.getStringWidth(available), 70, 0x87969f);
        fontRenderer.drawString(NetworkCenterGuiText.Resources.getLocal(), 22, 100, 0x87969f);
        if (container.resources.rows().isEmpty()) {
            GuiLabel.drawCentered(fontRenderer, NetworkCenterGuiText.NoResources.getLocal(), LIST_X, LIST_Y + 5, listWidth(), 10, 0x71828c, false);
        } else {
            for (int row = 0; row < Math.min(visibleRows, container.resources.rows().size()); row++)
                GuiLabel.draw(fontRenderer, container.resources.rows().get(scrollIndex + row).text(), LIST_X, LIST_Y + row * ROW_HEIGHT + 5,
                    listWidth(), 10, 0xd5e1e6, false);
        }
    }

    private int handleHeight() {
        return Math.max(8, visibleRows * ROW_HEIGHT * visibleRows / container.resources.rows().size());
    }

    private int handleY() {
        return LIST_Y + (int) ((long) scrollIndex * (visibleRows * ROW_HEIGHT - handleHeight()) / maxScroll());
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && maxScroll() > 0 && isPointInRegion(LIST_X, LIST_Y, listWidth() + 14, visibleRows * ROW_HEIGHT, mouseX, mouseY)) {
            scrollIndex = Math.clamp(scrollIndex - Integer.signum(delta), 0, maxScroll());
            updateView();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && maxScroll() > 0 && isPointInRegion(SCROLL_X, LIST_Y, SCROLL_WIDTH, visibleRows * ROW_HEIGHT, mouseX, mouseY)) {
            dragging = true;
            dragOffset = isPointInRegion(SCROLL_X, handleY(), SCROLL_WIDTH, handleHeight(), mouseX, mouseY)
                ? mouseY - guiTop - handleY() : handleHeight() / 2;
            drag(mouseY);
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    private void drag(int mouseY) {
        int travel = visibleRows * ROW_HEIGHT - handleHeight();
        int position = Math.clamp(mouseY - guiTop - LIST_Y - dragOffset, 0, travel);
        scrollIndex = (int) Math.round((double) position * maxScroll() / travel);
        updateView();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (dragging) drag(mouseY);
        else super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        dragging = false;
        super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (isPointInRegion(LIST_X, LIST_Y, listWidth(), visibleRows * ROW_HEIGHT, mouseX, mouseY)) {
            int row = scrollIndex + (mouseY - guiTop - LIST_Y) / ROW_HEIGHT;
            if (row < container.resources.rows().size()) {
                String text = container.resources.rows().get(row).text();
                if (fontRenderer.getStringWidth(text) > listWidth())
                    drawHoveringText(List.of(text), mouseX, mouseY, fontRenderer);
            }
        }
    }
}
