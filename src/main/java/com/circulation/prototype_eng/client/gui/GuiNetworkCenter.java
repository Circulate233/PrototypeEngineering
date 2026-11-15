package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.widgets.Scrollbar;
import com.circulation.prototype_eng.container.ContainerNetworkCenter;
import com.circulation.prototype_eng.localization.NetworkCenterGuiText;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

import static com.circulation.prototype_eng.client.gui.DeviceGuiDraw.*;

@SideOnly(Side.CLIENT)
public class GuiNetworkCenter extends AEBaseGui<ContainerNetworkCenter> {
    public static final int MIN_HEIGHT = 162;
    public static final int MAX_HEIGHT = 242;
    public static final int BASE_HEIGHT = 132;
    public static final int ROW_HEIGHT = 22;
    private static final int MAX_ROWS = (MAX_HEIGHT - BASE_HEIGHT) / ROW_HEIGHT;
    private static final int CELLS = 16, CELL_X = 86, CELL_WIDTH = 276, CELL_Y = 52, CELL_HEIGHT = 8;
    private static final int LIST_X = 20, LIST_Y = 112, SCROLL_X = 358;
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_network_center");
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private final DecimalFormat cellFormat = new DecimalFormat("0.##");
    private long knownConcurrency = -1;
    private int knownUsed = -1, knownLevel = -1, visibleRows = 1;
    private String concurrencyText = "", availableText = "", cellText = "", levelText = "", statusLabel = "";
    private String title, levelLabel, concurrencyLabel, resourceLabel, resourceHint, emptyLabel, powerHint;
    private NetworkCenterGuiText knownStatus;

    public GuiNetworkCenter(ContainerNetworkCenter container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = MIN_HEIGHT;
        scrollbar.setPosition(new Point(SCROLL_X, LIST_Y));
        scrollbar.setCaptureMouseWheel(false);
        widgets.add("resourceScroll", scrollbar);
    }

    private int rowCount() {
        return container.resources.rows().size();
    }

    private int listWidth() {
        return scrollbar.isVisible() ? 330 : 344;
    }

    private int targetHeight() {
        return Math.max(MIN_HEIGHT, BASE_HEIGHT + Math.clamp(rowCount(), 1, MAX_ROWS) * ROW_HEIGHT);
    }

    /**
     * Height follows the row count; a changed height re-runs layout so the screen stays centred.
     */
    private void updateView() {
        if (ySize != targetHeight()) initGui();
        visibleRows = Math.clamp(rowCount(), 1, MAX_ROWS);
        scrollbar.setHeight(visibleRows * ROW_HEIGHT);
        scrollbar.setRange(0, Math.max(0, rowCount() - MAX_ROWS), 1);
        scrollbar.setVisible(rowCount() > MAX_ROWS);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void initGui() {
        ySize = targetHeight();
        visibleRows = Math.clamp(rowCount(), 1, MAX_ROWS);
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
        powerHint = NetworkCenterGuiText.PowerHint.getLocal();
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
            availableText = NetworkCenterGuiText.Available.getLocal(GuiNumbers.grouped(Math.max(0, knownConcurrency - knownUsed)));
            cellText = knownConcurrency == 0 ? "" : NetworkCenterGuiText.PerCell.getLocal(cellFormat.format(knownConcurrency / (double) CELLS));
        }
        if (knownLevel != container.networkLevel) {
            knownLevel = container.networkLevel;
            levelText = knownLevel == 0 ? "—" : knownLevel < 10 ? "0" + knownLevel : Integer.toString(knownLevel);
        }
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize - 4, 384, MAX_HEIGHT);
        GuiAtlas.draw(BACKGROUND, x, y + ySize - 4, 0, MAX_HEIGHT - 4, xSize, 4, 384, MAX_HEIGHT);
        panel(x + 12, y + 26, 360, 58);
        panel(x + 12, y + 90, 360, ySize - 102);
        bordered(x + 20, y + 33, 52, 30, DARK, 0x2f2e3e);
        double filled = container.concurrency == 0 ? 0 : (double) CELLS * container.concurrencyUsed / container.concurrency;
        for (int cell = 0; cell < CELLS; cell++) {
            int left = CELL_X + cell * CELL_WIDTH / CELLS, cellWidth = CELL_X + (cell + 1) * CELL_WIDTH / CELLS - left - 2;
            double fill = Math.clamp(filled - cell, 0, 1);
            if (fill == 1) rect(x + left, y + CELL_Y, cellWidth, CELL_HEIGHT, DARK);
            else {
                bordered(x + left, y + CELL_Y, cellWidth, CELL_HEIGHT, SLOT, LINE);
                int part = (int) ((cellWidth - 2) * fill);
                if (part > 0) rect(x + left + 1, y + CELL_Y + 1, part, CELL_HEIGHT - 2, DARK);
            }
        }
        if (scrollbar.isVisible()) GuiAtlas.inset(x + SCROLL_X - 1, y + LIST_Y - 1, 9, visibleRows * ROW_HEIGHT + 2);
    }

    private int statusX() {
        return 370 - statusWidth(fontRenderer, statusLabel);
    }

    private int titleWidth() {
        return statusX() - 8 - 18;
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        text(fontRenderer, title, 18, 9, titleWidth(), TEXT);
        status(fontRenderer, statusLabel, statusX(), 9, statusTone());
        GlStateManager.pushMatrix();
        GlStateManager.translate(46 - fontRenderer.getStringWidth(levelText), 41, 0);
        GlStateManager.scale(2, 2, 1);
        fontRenderer.drawString(levelText, 0, 0, container.networkLevel == 0 ? MUTED : 0xcfe3ff);
        GlStateManager.popMatrix();
        centered(fontRenderer, levelLabel, 16, 66, 60, LINE_HEIGHT, SOFT);
        text(fontRenderer, concurrencyLabel, CELL_X, 37, 120, SOFT);
        right(fontRenderer, concurrencyText, CELL_X + CELL_WIDTH, 37, 150, TEXT);
        text(fontRenderer, cellText, CELL_X, 66, 120, SOFT);
        right(fontRenderer, availableText, CELL_X + CELL_WIDTH, 66, 150, SOFT);
        text(fontRenderer, resourceLabel, LIST_X, 97, 140, TEXT);
        right(fontRenderer, resourceHint, LIST_X + listWidth(), 97, 190, SOFT);
        var rows = container.resources.rows();
        if (rows.isEmpty()) {
            centered(fontRenderer, emptyLabel, LIST_X, LIST_Y, listWidth(), ROW_HEIGHT, SOFT);
            return;
        }
        int width = listWidth(), offset = scrollbar.getCurrentScroll();
        for (int row = 0; row < visibleRows && offset + row < rows.size(); row++) {
            var resource = rows.get(offset + row);
            int top = LIST_Y + row * ROW_HEIGHT;
            if (row > 0) rect(LIST_X, top - 1, width, 1, LINE);
            int amountWidth = Math.min(width * 3 / 5, fontRenderer.getStringWidth(resource.amountsText()));
            text(fontRenderer, resource.name(), LIST_X, top + 3, width - amountWidth - 8, TEXT);
            right(fontRenderer, resource.amountsText(), LIST_X + width, top + 3, amountWidth, TEXT);
            boolean full = resource.capacity() > 0 && resource.occupied() >= resource.capacity();
            progress(LIST_X, top + 15, width, 2, resource.capacity() == 0 ? 0 : resource.occupied() / resource.capacity(), full ? BAD : DARK);
        }
    }

    private Tone statusTone() {
        if (container.state == null) return Tone.BAD;
        return switch (container.state) {
            case ACTIVE_CENTER, ACTIVE_DATA_CENTER, ACTIVE_DEVICE -> Tone.GOOD;
            case SAME_TYPE_CONFLICT, NO_ACTIVE_CENTER -> Tone.WAIT;
            case UNFORMED, OWNER_TEAM_REJECTED, INSUFFICIENT_POWER, CENTER_LEVEL_INSUFFICIENT, INSUFFICIENT_THREADS ->
                Tone.BAD;
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

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0 || !scrollbar.isVisible()) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (isPointInRegion(LIST_X, LIST_Y, SCROLL_X + 7 - LIST_X, visibleRows * ROW_HEIGHT, mouseX, mouseY))
            scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - Integer.signum(delta));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        var tooltip = tooltip(mouseX, mouseY);
        if (tooltip != null) drawHoveringText(tooltip, mouseX, mouseY, fontRenderer);
    }

    private List<String> tooltip(int mouseX, int mouseY) {
        if (isPointInRegion(statusX(), 9, statusWidth(fontRenderer, statusLabel), LINE_HEIGHT, mouseX, mouseY))
            return knownStatus == NetworkCenterGuiText.NoPower ? List.of(statusLabel, powerHint) : List.of(statusLabel);
        if (isPointInRegion(18, 9, titleWidth(), LINE_HEIGHT, mouseX, mouseY))
            return fits(fontRenderer, title, titleWidth()) ? null : List.of(title);
        if (!isPointInRegion(LIST_X, LIST_Y, listWidth(), visibleRows * ROW_HEIGHT, mouseX, mouseY)) return null;
        int row = scrollbar.getCurrentScroll() + (mouseY - guiTop - LIST_Y) / ROW_HEIGHT;
        return row < rowCount() ? List.of(container.resources.rows().get(row).text()) : null;
    }
}
