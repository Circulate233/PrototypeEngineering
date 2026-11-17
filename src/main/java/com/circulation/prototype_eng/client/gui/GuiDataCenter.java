package com.circulation.prototype_eng.client.gui;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.widgets.AE2Button;
import ae2.client.gui.widgets.Scrollbar;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.container.ContainerDataCenter;
import com.circulation.prototype_eng.container.DataCenterResearchPacket;
import com.circulation.prototype_eng.client.research.ClientResearchContents;
import com.circulation.prototype_eng.localization.DataCenterGuiText;
import com.circulation.prototype_eng.localization.NetworkCenterGuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

import static com.circulation.prototype_eng.client.gui.DeviceGuiDraw.*;
import static com.circulation.prototype_eng.container.ContainerDataCenter.*;

@SideOnly(Side.CLIENT)
public class GuiDataCenter extends AEBaseGui<ContainerDataCenter> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_data_center");
    private static final int LIST_X = 18, LIST_Y = 48, ROW_HEIGHT = 20, ROWS = 5, SCROLL_X = 177;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private final AE2Button mode, choose, remove, confirm, cancel;
    private final ObjectArrayList<DisplayEntry> entries = new ObjectArrayList<>();
    private DataCenterResearchPacket knownStored, knownTarget;
    private @Nullable GenericStack knownPreview;
    private ItemStack previewItem = ItemStack.EMPTY;
    private @Nullable ResourceLocation selected;
    private int selectedIndex = -1, knownLevel = -1, knownCapacity = -1, knownLoss = -1;
    private NetworkCenterGuiText knownStatus;
    private @Nullable DataCenterGuiText knownReason;
    private boolean removing, knownImportMode;
    private String title, storedLabel, levelLabel, tierLabel, costLabel, inventoryLabel, removeHint;
    private String operationTitle, deniedText, emptyText;
    private String summary = "", statusLabel = "", selectedLabel = "", targetName = "", targetTier = "", cost = "", reason = "";
    private List<String> targetTooltip = List.of();

    public GuiDataCenter(ContainerDataCenter container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 290;
        mode = new AE2Button(0, 0, 58, 14, DataCenterGuiText.ImportMode.text(), container::changeMode);
        choose = new AE2Button(0, 0, 46, 14, DataCenterGuiText.SelectResearch.text(), container::openResearchOverview);
        remove = new AE2Button(0, 0, 166, 14, DataCenterGuiText.Remove.text(), () -> removing = true);
        confirm = new AE2Button(0, 0, 81, 14, DataCenterGuiText.ConfirmRemove.text(), () -> {
            container.removeResearch(selected);
            removing = false;
        });
        cancel = new AE2Button(0, 0, 81, 14, DataCenterGuiText.Cancel.text(), () -> removing = false);
        widgets.add("mode", mode, new Point(260, 32));
        widgets.add("choose", choose, new Point(320, 32));
        widgets.add("remove", remove, new Point(LIST_X, 168));
        widgets.add("confirm", confirm, new Point(LIST_X, 168));
        widgets.add("cancel", cancel, new Point(LIST_X + 85, 168));
        scrollbar.setPosition(new Point(SCROLL_X, LIST_Y));
        scrollbar.setHeight(ROWS * ROW_HEIGHT);
        scrollbar.setCaptureMouseWheel(false);
        widgets.add("storedResearch", scrollbar);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void initGui() {
        super.initGui();
        title = container.getGuiTitle().getFormattedText();
        storedLabel = DataCenterGuiText.Stored.getLocal();
        levelLabel = DataCenterGuiText.Level.getLocal();
        tierLabel = DataCenterGuiText.ResearchTier.getLocal();
        costLabel = DataCenterGuiText.ResearchCost.getLocal();
        inventoryLabel = I18n.format("container.inventory");
        removeHint = DataCenterGuiText.RemoveHint.getLocal();
        deniedText = DataCenterGuiText.Denied.getLocal();
        emptyText = DataCenterGuiText.Empty.getLocal();
        operationTitle = (container.importMode ? DataCenterGuiText.Import : DataCenterGuiText.Download).getLocal();
        knownStored = knownTarget = null;
        knownLevel = knownCapacity = knownLoss = -1;
        knownStatus = null;
        knownReason = null;
        reason = "";
        mode.setMessage((container.importMode ? DataCenterGuiText.DownloadMode : DataCenterGuiText.ImportMode).text());
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        boolean changedList = knownStored != container.stored;
        if (changedList) {
            knownStored = container.stored;
            entries.clear();
            selectedIndex = -1;
            for (int index = 0; index < knownStored.entries().size(); index++) {
                var entry = knownStored.entries().get(index);
                String name = ClientResearchContents.name(entry.id());
                entries.add(new DisplayEntry(entry, name, "Lv." + entry.level(),
                    List.of(name, DataCenterGuiText.StoredLevel.getLocal(entry.level(), entry.maxLevel()))));
                if (entry.id().equals(selected)) selectedIndex = index;
            }
            if (selectedIndex < 0) {
                removing = false;
                select(entries.isEmpty() ? -1 : 0);
            } else updateSelectedLabel();
            scrollbar.setRange(0, Math.max(0, entries.size() - ROWS), 1);
            scrollbar.setVisible(entries.size() > ROWS);
        }
        if (changedList || knownLevel != container.level || knownCapacity != container.capacity) {
            knownLevel = container.level;
            knownCapacity = container.capacity;
            summary = DataCenterGuiText.Summary.getLocal(container.level, container.visible ? entries.size() : "?", container.capacity);
        }
        var status = status();
        if (knownStatus != status) {
            knownStatus = status;
            statusLabel = status.getLocal();
        }
        if (knownImportMode != container.importMode) {
            knownImportMode = container.importMode;
            mode.setMessage((container.importMode ? DataCenterGuiText.DownloadMode : DataCenterGuiText.ImportMode).text());
            knownTarget = null;
            operationTitle = (container.importMode ? DataCenterGuiText.Import : DataCenterGuiText.Download).getLocal();
        }
        if (knownTarget != container.target) {
            knownTarget = container.target;
            var targets = knownTarget.entries();
            if (targets.isEmpty()) {
                targetName = (container.importMode ? DataCenterGuiText.InsertItem : DataCenterGuiText.NoSelection).getLocal();
                targetTier = "-";
                targetTooltip = List.of();
            } else {
                var tooltip = new ObjectArrayList<String>(targets.size());
                int tier = 0;
                for (int index = 0; index < targets.size(); index++) {
                    var entry = targets.get(index);
                    tooltip.add(ClientResearchContents.name(entry.id()) + " Lv." + entry.level());
                    tier = Math.max(tier, entry.tier());
                }
                targetName = targets.size() == 1 ? ClientResearchContents.name(targets.getFirst().id())
                    : DataCenterGuiText.MultipleResearch.getLocal(targets.size());
                targetTier = Integer.toString(tier);
                targetTooltip = tooltip;
            }
        }
        if (knownLoss != container.teamLevelLoss) {
            knownLoss = container.teamLevelLoss;
            cost = knownLoss == 0 ? DataCenterGuiText.None.getLocal() : DataCenterGuiText.TeamLoss.getLocal(knownLoss);
        }
        if (knownPreview != container.preview) {
            knownPreview = container.preview;
            previewItem = knownPreview == null ? ItemStack.EMPTY : ((AEItemKey) knownPreview.what()).toStack((int) knownPreview.amount());
        }
        var currentReason = reason();
        if (knownReason != currentReason) {
            knownReason = currentReason;
            reason = currentReason == null ? "" : currentReason.getLocal();
        }
        mode.enabled = container.visible;
        mode.x = guiLeft + (container.importMode ? 308 : 260);
        choose.visible = !container.importMode;
        choose.enabled = container.canTransfer;
        remove.visible = selectedIndex >= 0 && !removing;
        confirm.visible = cancel.visible = selectedIndex >= 0 && removing;
        remove.enabled = confirm.enabled = container.canRemove;
    }

    private void select(int index) {
        selectedIndex = index;
        selected = index < 0 ? null : entries.get(index).entry.id();
        removing = false;
        updateSelectedLabel();
    }

    private void updateSelectedLabel() {
        selectedLabel = selectedIndex < 0 ? "" : DataCenterGuiText.Selected.getLocal(entries.get(selectedIndex).name,
            entries.get(selectedIndex).entry.level());
    }

    private NetworkCenterGuiText status() {
        if (container.state == null) return NetworkCenterGuiText.NoState;
        return switch (container.state) {
            case ACTIVE_DATA_CENTER -> NetworkCenterGuiText.Active;
            case UNFORMED -> NetworkCenterGuiText.Unformed;
            case OWNER_TEAM_REJECTED -> NetworkCenterGuiText.TeamRejected;
            case NO_ACTIVE_CENTER -> NetworkCenterGuiText.NoActiveCenter;
            case CENTER_LEVEL_INSUFFICIENT -> NetworkCenterGuiText.LevelInsufficient;
            case INSUFFICIENT_THREADS -> NetworkCenterGuiText.NoConcurrency;
            case INSUFFICIENT_POWER -> NetworkCenterGuiText.NoPower;
            default -> NetworkCenterGuiText.Standby;
        };
    }

    private @Nullable DataCenterGuiText reason() {
        if (!container.visible) return DataCenterGuiText.Denied;
        if (container.savePending) return DataCenterGuiText.SavePending;
        if (container.transferPending) return DataCenterGuiText.Busy;
        if (container.state != NetworkWorkState.ACTIVE_DATA_CENTER) return DataCenterGuiText.Inactive;
        if (!container.slotsEditable) return DataCenterGuiText.ReadOnly;
        return container.operationState;
    }

    private int listWidth() {
        return scrollbar.isVisible() ? 156 : 166;
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, xSize, ySize);
        panel(x + 12, y + 27, 177, 158);
        panel(x + 195, y + 27, 177, 158);
        largeSlot(x + INPUT_X - 4, y + TRANSFER_Y - 4);
        largeSlot(x + OUTPUT_X - 4, y + TRANSFER_Y - 4);
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 9; column++)
                slot(x + PLAYER_X - 1 + column * 18, y + PLAYER_Y - 1 + row * 18);
        for (int column = 0; column < 9; column++) slot(x + PLAYER_X - 1 + column * 18, y + PLAYER_Y + 57);
        if (scrollbar.isVisible()) GuiAtlas.inset(x + SCROLL_X - 1, y + LIST_Y - 1, 9, ROWS * ROW_HEIGHT + 2);
    }

    private void largeSlot(int x, int y) {
        rect(x, y, 24, 24, SLOT);
        rect(x, y, 24, 1, PANEL);
        rect(x, y, 1, 24, PANEL);
        rect(x + 23, y, 1, 24, LIGHT);
        rect(x, y + 23, 24, 1, LIGHT);
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        int statusWidth = Math.min(105, fontRenderer.getStringWidth(statusLabel));
        int summaryRight = 366 - statusWidth - 8;
        int summaryWidth = Math.min(155, fontRenderer.getStringWidth(summary));
        text(fontRenderer, title, 18, 11, summaryRight - summaryWidth - 26, TEXT);
        right(fontRenderer, summary, summaryRight, 11, summaryWidth, TEXT);
        right(fontRenderer, statusLabel, 366, 11, statusWidth, container.state == NetworkWorkState.ACTIVE_DATA_CENTER ? GOOD : SOFT);
        text(fontRenderer, storedLabel, LIST_X, 35, 120, TEXT);
        right(fontRenderer, levelLabel, 184, 35, 40, TEXT);
        drawStored(mouseX, mouseY);
        rect(LIST_X, 150, 166, 1, LINE);
        text(fontRenderer, removing ? removeHint : selectedLabel, LIST_X, 155, 166, TEXT);
        text(fontRenderer, operationTitle, 202, 35, 52, TEXT);
        var targets = container.target.entries();
        int nameX = 202;
        if (!targets.isEmpty()) {
            var icon = ClientResearchContents.icon(targets.getFirst().id());
            if (icon != null) icon.draw(mc, 202, 51);
            nameX = 222;
        }
        text(fontRenderer, container.visible ? targetName : deniedText, nameX, 54, 366 - nameX, TEXT);
        text(fontRenderer, tierLabel, 202, 70, 82, TEXT);
        right(fontRenderer, targetTier, 366, 70, 78, TEXT);
        text(fontRenderer, costLabel, 202, 82, 62, TEXT);
        right(fontRenderer, cost, 366, 82, 98, container.teamLevelLoss == 0 ? TEXT : BAD);
        int arrowColor = container.preview != null ? DARK : MUTED;
        rect(271, 106, 18, 3, arrowColor);
        for (int index = 0; index < 7; index++) rect(289 + index, 101 + index, 1, 13 - 2 * index, arrowColor);
        int reasonWidth = Math.min(164, fontRenderer.getStringWidth(reason));
        text(fontRenderer, reason, 202 + (164 - reasonWidth) / 2, 129, reasonWidth,
            knownReason == DataCenterGuiText.Completed ? GOOD : BAD);
        text(fontRenderer, inventoryLabel, PLAYER_X - 1, 192, 162, TEXT);
    }

    private void drawStored(int mouseX, int mouseY) {
        if (entries.isEmpty()) {
            centered(fontRenderer, container.visible ? emptyText : deniedText,
                LIST_X, LIST_Y, 166, ROWS * ROW_HEIGHT, SOFT);
            return;
        }
        int first = scrollbar.getCurrentScroll();
        int width = listWidth();
        for (int index = first; index < Math.min(entries.size(), first + ROWS); index++) {
            var entry = entries.get(index);
            int top = LIST_Y + (index - first) * ROW_HEIGHT;
            if (index == selectedIndex) rect(LIST_X, top, width, ROW_HEIGHT, SLOT);
            else if (isPointInRegion(LIST_X, top, width, ROW_HEIGHT, mouseX, mouseY))
                rect(LIST_X, top, width, ROW_HEIGHT, HOVER);
            if (index > first) rect(LIST_X, top, width, 1, LINE);
            var icon = ClientResearchContents.icon(entry.entry.id());
            if (icon != null) icon.draw(mc, LIST_X + 3, top + 2);
            int levelWidth = Math.min(48, fontRenderer.getStringWidth(entry.level));
            text(fontRenderer, entry.name, LIST_X + 23, top + 5, width - 29 - levelWidth, TEXT);
            right(fontRenderer, entry.level, LIST_X + width - 3, top + 5, levelWidth, TEXT);
        }
    }

    @Override
    protected void drawSlot(@NotNull Slot slot) {
        super.drawSlot(slot);
        if (slot.slotNumber != 1 || slot.getHasStack() || container.preview == null) return;
        itemRender.renderItemAndEffectIntoGUI(previewItem, slot.xPos, slot.yPos);
        itemRender.renderItemOverlayIntoGUI(fontRenderer, previewItem, slot.xPos, slot.yPos, null);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        drawRect(slot.xPos, slot.yPos, slot.xPos + 16, slot.yPos + 16, 0x90adb0c4);
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
    }

    @Override
    protected void handleMouseClick(@Nullable Slot slot, int slotId, int mouseButton, @NotNull ClickType clickType) {
        if (slotId == 1 && !container.getSlot(1).getHasStack() && container.preview != null && mouseButton == 0
            && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)
            && mc.player.inventory.getItemStack().isEmpty()) {
            container.performTransfer();
            return;
        }
        super.handleMouseClick(slot, slotId, mouseButton, clickType);
    }

    private int rowAt(int mouseX, int mouseY) {
        if (!isPointInRegion(LIST_X, LIST_Y, listWidth(), ROWS * ROW_HEIGHT, mouseX, mouseY)) return -1;
        int index = scrollbar.getCurrentScroll() + (mouseY - guiTop - LIST_Y) / ROW_HEIGHT;
        return index < entries.size() ? index : -1;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        int index = button == 0 ? rowAt(mouseX, mouseY) : -1;
        if (index >= 0) {
            select(index);
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0 || !scrollbar.isVisible()) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (isPointInRegion(LIST_X, LIST_Y, 166, ROWS * ROW_HEIGHT, mouseX, mouseY))
            scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - Integer.signum(delta));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        var tooltip = tooltip(mouseX, mouseY);
        if (tooltip != null) drawHoveringText(tooltip, mouseX, mouseY, fontRenderer);
    }

    private @Nullable List<String> tooltip(int mouseX, int mouseY) {
        int index = rowAt(mouseX, mouseY);
        if (index >= 0) return entries.get(index).tooltip;
        if (isPointInRegion(18, 11, 348, LINE_HEIGHT, mouseX, mouseY)) {
            int statusWidth = Math.min(105, fontRenderer.getStringWidth(statusLabel));
            int summaryRight = 366 - statusWidth - 8;
            int summaryWidth = Math.min(155, fontRenderer.getStringWidth(summary));
            int titleWidth = summaryRight - summaryWidth - 26;
            if (isPointInRegion(18, 11, titleWidth, LINE_HEIGHT, mouseX, mouseY) && !fits(fontRenderer, title, titleWidth))
                return List.of(title);
            if (isPointInRegion(summaryRight - summaryWidth, 11, summaryWidth, LINE_HEIGHT, mouseX, mouseY)
                && !fits(fontRenderer, summary, summaryWidth)) return List.of(summary);
            if (isPointInRegion(366 - statusWidth, 11, statusWidth, LINE_HEIGHT, mouseX, mouseY)
                && !fits(fontRenderer, statusLabel, statusWidth)) return List.of(statusLabel);
        }
        if (selectedIndex >= 0 && isPointInRegion(LIST_X, 155, 166, LINE_HEIGHT, mouseX, mouseY)) {
            String label = removing ? removeHint : selectedLabel;
            if (!fits(fontRenderer, label, 166)) return List.of(label);
        }
        if (isPointInRegion(202, 51, 164, 16, mouseX, mouseY) && !targetTooltip.isEmpty()) return targetTooltip;
        if (isPointInRegion(202, 82, 164, LINE_HEIGHT, mouseX, mouseY) && !fits(fontRenderer, cost, 98))
            return List.of(cost);
        if (isPointInRegion(202, 124, 164, 20, mouseX, mouseY) && !fits(fontRenderer, reason, 164))
            return List.of(reason);
        if (isPointInRegion(OUTPUT_X, TRANSFER_Y, 16, 16, mouseX, mouseY)
            && container.preview != null && !container.getSlot(1).getHasStack()) {
            var lines = getItemToolTip(previewItem);
            lines.add((container.importMode ? DataCenterGuiText.ImportPreview : DataCenterGuiText.DownloadPreview).getLocal());
            return lines;
        }
        if (isPointInRegion(INPUT_X, TRANSFER_Y, 16, 16, mouseX, mouseY) && !container.getSlot(0).getHasStack())
            return List.of(DataCenterGuiText.Input.getLocal());
        if (isPointInRegion(OUTPUT_X, TRANSFER_Y, 16, 16, mouseX, mouseY) && !container.getSlot(1).getHasStack())
            return List.of(DataCenterGuiText.Output.getLocal());
        return null;
    }

    private record DisplayEntry(DataCenterResearchPacket.Entry entry, String name, String level, List<String> tooltip) {
    }
}
