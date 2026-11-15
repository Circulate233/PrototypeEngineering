package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.container.ContainerNetworkCenter;
import com.circulation.prototype_eng.localization.NetworkCenterGuiText;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiNetworkCenter extends AEBaseGui<ContainerNetworkCenter> {
    public static final int MIN_HEIGHT = 162;
    public static final int MAX_HEIGHT = 242;
    public static final int BASE_HEIGHT = 132;
    public static final int ROW_HEIGHT = 22;
    private static final int MAX_ROWS = 5;
    private static final int LIST_X = 20, LIST_Y = 114, SCROLL_X = 360, SCROLL_WIDTH = 6;
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_network_center");
    private final DecimalFormat cellFormat = new DecimalFormat("0.##");
    private long knownConcurrency = -1;
    private int knownUsed = -1, knownLevel = -1;
    private String concurrencyText = "", availableText = "", cellText = "", levelText = "";
    private String title, levelLabel, concurrencyLabel, resourceLabel, resourceHint, emptyLabel, statusLabel;
    private NetworkCenterGuiText knownStatus;
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
        return maxScroll() > 0 ? 330 : 344;
    }

    private void updateView() {
        visibleRows = Math.clamp(container.resources.rows().size(), 1, MAX_ROWS);
        ySize = Math.max(MIN_HEIGHT, BASE_HEIGHT + visibleRows * ROW_HEIGHT);
        guiTop = (height - ySize) / 2;
        scrollIndex = Math.clamp(scrollIndex, 0, maxScroll());
        if (maxScroll() == 0) dragging = false;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void initGui() {
        super.initGui();
        container.resources.invalidateText();
        knownConcurrency = -1;
        knownUsed = -1;
        knownLevel = -1;
        knownStatus = null;
        title = container.getGuiTitle().getFormattedText();
        levelLabel = NetworkCenterGuiText.Level.getLocal();
        concurrencyLabel = NetworkCenterGuiText.Concurrency.getLocal();
        resourceLabel = NetworkCenterGuiText.Resources.getLocal();
        resourceHint = NetworkCenterGuiText.OccupiedSupply.getLocal();
        emptyLabel = NetworkCenterGuiText.NoResources.getLocal();
        updateView();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        updateView();
        var currentStatus = statusText();
        if (knownStatus != currentStatus) {
            knownStatus = currentStatus;
            statusLabel = currentStatus.getLocal();
        }
        if (knownConcurrency != container.concurrency || knownUsed != container.concurrencyUsed) {
            knownConcurrency = container.concurrency;
            knownUsed = container.concurrencyUsed;
            concurrencyText = GuiNumbers.grouped(knownUsed) + " / " + GuiNumbers.grouped(knownConcurrency);
            availableText = NetworkCenterGuiText.Available.getLocal(GuiNumbers.grouped(knownConcurrency - knownUsed));
            cellText = knownConcurrency == 0 ? "" : NetworkCenterGuiText.PerCell.getLocal(cellFormat.format(knownConcurrency / 16D));
        }
        if (knownLevel != container.networkLevel) {
            knownLevel = container.networkLevel;
            levelText = knownLevel == 0 ? "—" : (knownLevel < 10 ? "0" : "") + knownLevel;
        }
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize - 4, 384, MAX_HEIGHT);
        GuiAtlas.draw(BACKGROUND, x, y + ySize - 4, 0, MAX_HEIGHT - 4, xSize, 4, 384, MAX_HEIGHT);
        DeviceGuiDraw.panel(x + 12, y + 28, 360, 58);
        DeviceGuiDraw.panel(x + 12, y + 92, 360, ySize - 104);
        DeviceGuiDraw.bordered(x + 20, y + 36, 50, 30, DeviceGuiDraw.DARK, 0x2f2e3e);
        double filledCells = container.concurrency == 0 ? 0 : 16D * container.concurrencyUsed / container.concurrency;
        for (int cell = 0; cell < 16; cell++) {
            int left = 86 + cell * 276 / 16, next = 86 + (cell + 1) * 276 / 16;
            int cellWidth = next - left - 1;
            double fill = Math.clamp(filledCells - cell, 0, 1);
            if (fill == 1) DeviceGuiDraw.rect(x + left, y + 54, cellWidth, 8, DeviceGuiDraw.DARK);
            else {
                DeviceGuiDraw.bordered(x + left, y + 54, cellWidth, 8, DeviceGuiDraw.SLOT, DeviceGuiDraw.LINE);
                if (fill > 0)
                    DeviceGuiDraw.rect(x + left + 1, y + 55, (int) ((cellWidth - 2) * fill), 6, DeviceGuiDraw.DARK);
            }
        }
        if (maxScroll() > 0) {
            int length = visibleRows * ROW_HEIGHT;
            Gui.drawRect(x + SCROLL_X, y + LIST_Y, x + SCROLL_X + SCROLL_WIDTH, y + LIST_Y + length, 0xff696d88);
            int handle = handleY();
            Gui.drawRect(x + SCROLL_X, y + handle, x + SCROLL_X + SCROLL_WIDTH, y + handle + handleHeight(), dragging ? 0xfff2f2f2 : 0xffadb0c4);
        }
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        int statusLeft = 370 - DeviceGuiDraw.statusWidth(fontRenderer, statusLabel);
        DeviceGuiDraw.text(fontRenderer, title, 18, 10, statusLeft - 26, DeviceGuiDraw.TEXT);
        DeviceGuiDraw.status(fontRenderer, statusLabel, statusLeft, 10, statusTone());
        GlStateManager.pushMatrix();
        GlStateManager.translate(45 - fontRenderer.getStringWidth(levelText), 43, 0);
        GlStateManager.scale(2, 2, 1);
        fontRenderer.drawString(levelText, 0, 0, container.networkLevel == 0 ? DeviceGuiDraw.MUTED : 0xcfe3ff);
        GlStateManager.popMatrix();
        GuiLabel.drawCentered(fontRenderer, levelLabel, 18, 69, 54, 10, DeviceGuiDraw.SOFT, false);
        DeviceGuiDraw.text(fontRenderer, concurrencyLabel, 86, 39, 135, DeviceGuiDraw.SOFT);
        DeviceGuiDraw.right(fontRenderer, concurrencyText, 362, 37, 140, 14, DeviceGuiDraw.TEXT);
        DeviceGuiDraw.text(fontRenderer, cellText, 86, 68, 140, DeviceGuiDraw.SOFT);
        DeviceGuiDraw.right(fontRenderer, availableText, 362, 68, 135, 10, DeviceGuiDraw.SOFT);
        DeviceGuiDraw.text(fontRenderer, resourceLabel, LIST_X, 98, 138, DeviceGuiDraw.TEXT);
        DeviceGuiDraw.right(fontRenderer, resourceHint, LIST_X + listWidth(), 98, 190, 10, DeviceGuiDraw.SOFT);
        if (container.resources.rows().isEmpty()) {
            GuiLabel.drawCentered(fontRenderer, emptyLabel, LIST_X, LIST_Y + 5, listWidth(), 10, DeviceGuiDraw.SOFT, false);
        } else {
            for (int row = 0; row < visibleRows; row++) {
                var resource = container.resources.rows().get(scrollIndex + row);
                int top = LIST_Y + row * ROW_HEIGHT;
                if (row > 0) DeviceGuiDraw.rect(LIST_X, top - 1, listWidth(), 1, DeviceGuiDraw.LINE);
                int amountWidth = Math.min(listWidth() * 3 / 4, fontRenderer.getStringWidth(resource.amountsText()));
                DeviceGuiDraw.text(fontRenderer, resource.name(), LIST_X, top + 2, listWidth() - amountWidth - 8, DeviceGuiDraw.TEXT);
                DeviceGuiDraw.right(fontRenderer, resource.amountsText(), LIST_X + listWidth(), top + 2, amountWidth, 10, DeviceGuiDraw.TEXT);
                DeviceGuiDraw.progress(LIST_X, top + 15, listWidth(), 2,
                    resource.capacity() == 0 ? 0 : resource.occupied() / resource.capacity(),
                    resource.occupied() >= resource.capacity() ? DeviceGuiDraw.BAD : DeviceGuiDraw.DARK);
            }
        }
    }

    private DeviceGuiDraw.Tone statusTone() {
        if (container.state == null) return DeviceGuiDraw.Tone.BAD;
        return switch (container.state) {
            case ACTIVE_CENTER, ACTIVE_DATA_CENTER, ACTIVE_DEVICE -> DeviceGuiDraw.Tone.GOOD;
            case SAME_TYPE_CONFLICT, NO_ACTIVE_CENTER -> DeviceGuiDraw.Tone.WAIT;
            default -> DeviceGuiDraw.Tone.BAD;
        };
    }

    private NetworkCenterGuiText statusText() {
        if (container.state == null) return NetworkCenterGuiText.NoState;
        return switch (container.state) {
            case ACTIVE_CENTER, ACTIVE_DATA_CENTER, ACTIVE_DEVICE -> NetworkCenterGuiText.Active;
            case UNFORMED -> NetworkCenterGuiText.Unformed;
            case OWNER_TEAM_REJECTED -> NetworkCenterGuiText.TeamRejected;
            case INSUFFICIENT_POWER -> NetworkCenterGuiText.NoPower;
            case NO_ACTIVE_CENTER -> NetworkCenterGuiText.NoActiveCenter;
            case CENTER_LEVEL_INSUFFICIENT -> NetworkCenterGuiText.LevelInsufficient;
            case SAME_TYPE_CONFLICT -> NetworkCenterGuiText.Standby;
            case INSUFFICIENT_THREADS -> NetworkCenterGuiText.NoConcurrency;
        };
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
        int statusWidth = DeviceGuiDraw.statusWidth(fontRenderer, statusLabel);
        if (isPointInRegion(370 - statusWidth, 10, statusWidth, 10, mouseX, mouseY)) {
            drawHoveringText(knownStatus == NetworkCenterGuiText.NoPower
                ? List.of(statusLabel, NetworkCenterGuiText.PowerHint.getLocal()) : List.of(statusLabel), mouseX, mouseY, fontRenderer);
        } else if (isPointInRegion(18, 10, 344 - statusWidth, 10, mouseX, mouseY)
            && fontRenderer.getStringWidth(title) > 344 - statusWidth) {
            drawHoveringText(List.of(title), mouseX, mouseY, fontRenderer);
        }
        if (isPointInRegion(LIST_X, LIST_Y, listWidth(), visibleRows * ROW_HEIGHT, mouseX, mouseY)) {
            int row = scrollIndex + (mouseY - guiTop - LIST_Y) / ROW_HEIGHT;
            if (row < container.resources.rows().size()) {
                String text = container.resources.rows().get(row).text();
                drawHoveringText(List.of(text), mouseX, mouseY, fontRenderer);
            }
        }
    }
}
