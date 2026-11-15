package com.circulation.prototype_eng.client.gui;

import ae2.api.client.AEKeyRendering;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.widgets.AE2Button;
import ae2.client.gui.widgets.Scrollbar;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.container.ComputerDetailsPacket;
import com.circulation.prototype_eng.container.ContainerComputer;
import com.circulation.prototype_eng.localization.ComputerGuiText;
import com.circulation.prototype_eng.localization.MachineGuiText;
import com.circulation.prototype_eng.localization.NetworkCenterGuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

import static com.circulation.prototype_eng.client.gui.DeviceGuiDraw.*;

public class GuiComputer extends AEBaseGui<ContainerComputer> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_network_center");
    private static final int ALERT_Y = 28, ALERT_HEIGHT = 18, STAT_HEIGHT = 30, STAT_WIDTH = 116, TAB_HEIGHT = 14;
    private static final int PANEL_X = 12, PANEL_WIDTH = 360, PANEL_BOTTOM = 230;
    private static final int COMPONENT_ROW = 18, RESOURCE_ROW = 18, DETAIL_HEIGHT = 24;
    private static final int LEFT_X = 20, RIGHT_X = 202, COLUMN_WIDTH = 162, SCROLL_WIDTH = 7;
    private final PageTab[] tabs = new PageTab[2];
    private final Scrollbar listScroll = new Scrollbar(Scrollbar.SMALL), demandScroll = new Scrollbar(Scrollbar.SMALL),
        outputScroll = new Scrollbar(Scrollbar.SMALL);
    private int page, selectedComponent, layoutStatsY = -1, statsY, bodyY, listY, resourceY, detailY, componentRows, resourceRows;
    private List<ComputerDetailsPacket.Component> knownComponents;
    private final ObjectArrayList<ComponentText> componentText = new ObjectArrayList<>();
    private NetworkWorkState knownNetwork;
    private ComputerController.SupplyState knownSupply;
    private boolean knownFormed, stateKnown;
    private String stateText = "", structureText = "", alertText = "";
    private long knownConcurrency = -1, knownInstalled = -1, knownPaid = -1, knownRequired = -1, knownAvailable = -1;
    private String concurrencyText = "", installedText = "", energyText = "", availableText = "";
    private String titleText, concurrencyLabel, installedLabel, demandLabel, demandHint, outputLabel, outputHint;
    private String energyLabel, availableLabel, componentLabel, countLabel, concurrencyColumn, energyColumn, stateLabel;
    private String runningLabel, pausedLabel, emptyComponents, emptyOutput, requiredLabel, providedLabel, noneLabel;

    public GuiComputer(ContainerComputer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 242;
        var labels = new ComputerGuiText[]{ComputerGuiText.Overview, ComputerGuiText.Components};
        for (int index = 0; index < tabs.length; index++) {
            tabs[index] = new PageTab(index, labels[index]);
            widgets.add("page" + index, tabs[index]);
        }
        listScroll.setCaptureMouseWheel(false);
        demandScroll.setCaptureMouseWheel(false);
        outputScroll.setCaptureMouseWheel(false);
        widgets.add("listScroll", listScroll);
        widgets.add("demandScroll", demandScroll);
        widgets.add("outputScroll", outputScroll);
    }

    private void selectPage(int value) {
        page = value;
        listScroll.setCurrentScroll(0);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void initGui() {
        super.initGui();
        knownComponents = null;
        stateKnown = false;
        layoutStatsY = -1;
        knownConcurrency = knownInstalled = knownPaid = knownRequired = knownAvailable = -1;
        titleText = container.getGuiTitle().getFormattedText();
        concurrencyLabel = ComputerGuiText.ConcurrencyLabel.getLocal();
        installedLabel = ComputerGuiText.InstalledLabel.getLocal();
        demandLabel = ComputerGuiText.ComponentResources.getLocal();
        demandHint = ComputerGuiText.AvailableDemand.getLocal();
        outputLabel = ComputerGuiText.OutputResources.getLocal();
        outputHint = ComputerGuiText.PerTick.getLocal();
        energyLabel = ComputerGuiText.EnergyUse.getLocal();
        availableLabel = ComputerGuiText.EnergyAvailable.getLocal();
        componentLabel = ComputerGuiText.Component.getLocal();
        countLabel = ComputerGuiText.Count.getLocal();
        concurrencyColumn = ComputerGuiText.ComponentConcurrency.getLocal();
        energyColumn = ComputerGuiText.EnergyPerTick.getLocal();
        stateLabel = ComputerGuiText.State.getLocal();
        runningLabel = ComputerGuiText.RecipeRunning.getLocal();
        pausedLabel = ComputerGuiText.Paused.getLocal();
        emptyComponents = ComputerGuiText.EmptyComponents.getLocal();
        emptyOutput = NetworkCenterGuiText.NoResources.getLocal();
        requiredLabel = ComputerGuiText.RequiredPerItemLabel.getLocal();
        providedLabel = ComputerGuiText.ProvidedPerItemLabel.getLocal();
        noneLabel = ComputerGuiText.None.getLocal();
        for (var tab : tabs) tab.title = tab.label.getLocal();
        container.demandView.invalidateText();
        container.outputView.invalidateText();
    }

    private boolean shortage() {
        return container.supplyState == ComputerController.SupplyState.RESOURCE_SHORTAGE
            || container.supplyState == ComputerController.SupplyState.ENERGY_SHORTAGE;
    }

    /**
     * The alert bar only exists while components are short; everything below it moves with it.
     */
    private void layout() {
        statsY = shortage() ? ALERT_Y + ALERT_HEIGHT + 4 : ALERT_Y;
        if (layoutStatsY == statsY) return;
        layoutStatsY = statsY;
        bodyY = statsY + STAT_HEIGHT + 4 + TAB_HEIGHT;
        detailY = PANEL_BOTTOM - 6 - DETAIL_HEIGHT;
        listY = bodyY + 18;
        resourceY = bodyY + 20;
        componentRows = (detailY - 3 - listY) / COMPONENT_ROW;
        resourceRows = (PANEL_BOTTOM - 6 - resourceY) / RESOURCE_ROW;
        for (int index = 0; index < tabs.length; index++) {
            tabs[index].x = guiLeft + PANEL_X + 6 + index * (PageTab.WIDTH - 1);
            tabs[index].y = guiTop + bodyY - TAB_HEIGHT;
        }
        listScroll.setPosition(new Point(PANEL_X + PANEL_WIDTH - 6 - SCROLL_WIDTH, listY));
        listScroll.setHeight(componentRows * COMPONENT_ROW);
        demandScroll.setPosition(new Point(LEFT_X + COLUMN_WIDTH - SCROLL_WIDTH, resourceY));
        demandScroll.setHeight(resourceRows * RESOURCE_ROW);
        outputScroll.setPosition(new Point(RIGHT_X + COLUMN_WIDTH - 2 - SCROLL_WIDTH, resourceY));
        outputScroll.setHeight(resourceRows * RESOURCE_ROW);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        layout();
        configureScroll(listScroll, container.components.size(), componentRows, page != 0);
        configureScroll(demandScroll, container.demandView.rows().size() + 2, resourceRows, page == 0);
        configureScroll(outputScroll, container.outputView.rows().size(), resourceRows, page == 0);
        if (knownComponents != container.components) {
            knownComponents = container.components;
            componentText.clear();
            for (int index = 0; index < knownComponents.size(); index++) {
                var row = knownComponents.get(index);
                String name = row.item().getDisplayName().getFormattedText();
                String required = resources(row.required()) + ", FE " + GuiNumbers.grouped(row.energy()) + "/t";
                String provided = resources(row.provided()) + ", " + concurrencyLabel + " " + GuiNumbers.grouped(row.concurrency());
                componentText.add(new ComponentText(name, "×" + GuiNumbers.grouped(row.count()),
                    GuiNumbers.grouped(Math.multiplyExact(row.count(), row.concurrency())),
                    GuiNumbers.grouped(Math.multiplyExact(row.count(), row.energy())), required, provided,
                    List.of(name, requiredLabel + ": " + required, providedLabel + ": " + provided)));
            }
            selectedComponent = Math.clamp(selectedComponent, 0, Math.max(0, componentText.size() - 1));
        }
        if (!stateKnown || knownNetwork != container.networkState || knownSupply != container.supplyState) {
            stateKnown = true;
            knownNetwork = container.networkState;
            knownSupply = container.supplyState;
            stateText = stateMessage().getLocal();
            alertText = container.supplyState == ComputerController.SupplyState.ENERGY_SHORTAGE
                ? ComputerGuiText.EnergyShortage.getLocal() : ComputerGuiText.ResourceShortage.getLocal();
        }
        if (structureText.isEmpty() || knownFormed != container.formed) {
            knownFormed = container.formed;
            structureText = (container.formed ? MachineGuiText.Formed : MachineGuiText.NotFormed).getLocal();
        }
        if (knownConcurrency != container.concurrency) {
            knownConcurrency = container.concurrency;
            concurrencyText = GuiNumbers.grouped(container.concurrency);
        }
        if (knownInstalled != container.installedCount) {
            knownInstalled = container.installedCount;
            installedText = ComputerGuiText.InstalledCount.getLocal(GuiNumbers.grouped(container.installedCount));
        }
        if (knownPaid != container.energyPaid || knownRequired != container.energyRequired) {
            knownPaid = container.energyPaid;
            knownRequired = container.energyRequired;
            energyText = GuiNumbers.grouped(container.energyPaid) + " / " + GuiNumbers.grouped(container.energyRequired);
        }
        if (knownAvailable != container.energyAvailable) {
            knownAvailable = container.energyAvailable;
            availableText = GuiNumbers.grouped(container.energyAvailable);
        }
    }

    private static void configureScroll(Scrollbar scrollbar, int size, int visible, boolean pageVisible) {
        scrollbar.setRange(0, Math.max(0, size - visible), 1);
        scrollbar.setVisible(pageVisible && size > visible);
    }

    private String resources(Reference2DoubleMap<NetworkResourceType> values) {
        var labels = new StringBuilder();
        for (var entry : values.reference2DoubleEntrySet()) {
            if (entry.getDoubleValue() != 0) {
                if (!labels.isEmpty()) labels.append(", ");
                labels.append(entry.getKey().displayName()).append(' ').append(entry.getKey().format(entry.getDoubleValue()));
            }
        }
        return labels.isEmpty() ? noneLabel : labels.toString();
    }

    private int demandWidth() {
        return COLUMN_WIDTH - (demandScroll.isVisible() ? SCROLL_WIDTH + 3 : 0);
    }

    private int outputWidth() {
        return COLUMN_WIDTH - 2 - (outputScroll.isVisible() ? SCROLL_WIDTH + 3 : 0);
    }

    private int componentWidth() {
        return PANEL_WIDTH - 12 - (listScroll.isVisible() ? SCROLL_WIDTH + 3 : 0);
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, 384, 242);
        if (shortage())
            bordered(x + PANEL_X, y + ALERT_Y, PANEL_WIDTH, ALERT_HEIGHT, Tone.BAD.background, Tone.BAD.border);
        for (int index = 0; index < 3; index++)
            panel(x + PANEL_X + index * (STAT_WIDTH + 6), y + statsY, STAT_WIDTH, STAT_HEIGHT);
        panel(x + PANEL_X, y + bodyY, PANEL_WIDTH, PANEL_BOTTOM - bodyY);
        if (page == 0) rect(x + 192, y + bodyY + 6, 1, PANEL_BOTTOM - bodyY - 12, LINE);
        else rect(x + LEFT_X, y + detailY - 3, PANEL_WIDTH - 16, 1, LINE);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        Scrollbar target = null;
        if (page != 0 && isPointInRegion(PANEL_X + 6, listY, PANEL_WIDTH - 12, componentRows * COMPONENT_ROW, mouseX, mouseY))
            target = listScroll;
        else if (page == 0 && isPointInRegion(LEFT_X, resourceY, COLUMN_WIDTH, resourceRows * RESOURCE_ROW, mouseX, mouseY))
            target = demandScroll;
        else if (page == 0 && isPointInRegion(RIGHT_X, resourceY, COLUMN_WIDTH, resourceRows * RESOURCE_ROW, mouseX, mouseY))
            target = outputScroll;
        if (target != null && target.isVisible())
            target.setCurrentScroll(target.getCurrentScroll() - Integer.signum(delta));
    }

    private LocalizationEnum stateMessage() {
        if (container.networkState == null) return NetworkCenterGuiText.NoState;
        return switch (container.networkState) {
            case UNFORMED -> NetworkCenterGuiText.Unformed;
            case OWNER_TEAM_REJECTED -> NetworkCenterGuiText.TeamRejected;
            case NO_ACTIVE_CENTER -> NetworkCenterGuiText.NoActiveCenter;
            case CENTER_LEVEL_INSUFFICIENT -> NetworkCenterGuiText.LevelInsufficient;
            case INSUFFICIENT_THREADS -> NetworkCenterGuiText.NoConcurrency;
            case INSUFFICIENT_POWER -> NetworkCenterGuiText.NoPower;
            case SAME_TYPE_CONFLICT -> NetworkCenterGuiText.Standby;
            case ACTIVE_CENTER, ACTIVE_DATA_CENTER -> NetworkCenterGuiText.Active;
            case ACTIVE_DEVICE -> switch (container.supplyState) {
                case ACTIVE -> ComputerGuiText.Running;
                case RESOURCE_SHORTAGE, ENERGY_SHORTAGE -> ComputerGuiText.Stopped;
                case WAITING -> ComputerGuiText.RecipeWaiting;
            };
        };
    }

    private Tone statusTone() {
        if (container.networkState == NetworkWorkState.SAME_TYPE_CONFLICT || container.networkState == NetworkWorkState.NO_ACTIVE_CENTER)
            return Tone.WAIT;
        if (container.networkState != NetworkWorkState.ACTIVE_DEVICE) return Tone.BAD;
        return switch (container.supplyState) {
            case ACTIVE -> Tone.GOOD;
            case WAITING -> Tone.WAIT;
            case RESOURCE_SHORTAGE, ENERGY_SHORTAGE -> Tone.BAD;
        };
    }

    private int statusX() {
        return 372 - statusWidth(fontRenderer, stateText);
    }

    private int structureWidth() {
        return Math.min(90, fontRenderer.getStringWidth(structureText));
    }

    private int titleWidth() {
        return statusX() - 8 - structureWidth() - 8 - 18;
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        int titleWidth = Math.min(titleWidth(), fontRenderer.getStringWidth(titleText));
        text(fontRenderer, titleText, 18, 10, titleWidth, TEXT);
        text(fontRenderer, structureText, 18 + titleWidth + 8, 10, structureWidth(), SOFT);
        status(fontRenderer, stateText, statusX(), 10, statusTone());
        if (shortage()) {
            Icon.INVALID.getBlitter().dest(PANEL_X + 4, ALERT_Y + 1, 16, 16).blit();
            text(fontRenderer, alertText, PANEL_X + 24, ALERT_Y + 4, PANEL_WIDTH - 30, BAD);
        }
        stat(concurrencyLabel, concurrencyText, 0, TEXT);
        stat(installedLabel, installedText, 1, TEXT);
        stat(energyLabel, energyText, 2, container.energyPaid < container.energyRequired ? BAD : TEXT);
        if (page == 0) drawOverview();
        else drawComponents(mouseX, mouseY);
    }

    private void stat(String title, String value, int index, int color) {
        int x = PANEL_X + index * (STAT_WIDTH + 6) + 7;
        text(fontRenderer, title, x, statsY + 4, STAT_WIDTH - 14, SOFT);
        text(fontRenderer, value, x, statsY + 17, STAT_WIDTH - 14, color);
    }

    private void pair(String name, String amount, int x, int y, int width, int color) {
        int amountWidth = Math.min(fontRenderer.getStringWidth(amount), width * 3 / 5);
        text(fontRenderer, name, x, y, width - amountWidth - 6, TEXT);
        right(fontRenderer, amount, x + width, y, amountWidth, color);
    }

    private static double fraction(double available, double required) {
        return required == 0 ? 1 : available / required;
    }

    private void drawOverview() {
        int headerY = bodyY + 6;
        text(fontRenderer, demandLabel, LEFT_X, headerY, 80, TEXT);
        right(fontRenderer, demandHint, LEFT_X + COLUMN_WIDTH, headerY, COLUMN_WIDTH - 86, SOFT);
        var demands = container.demandView.rows();
        int width = demandWidth();
        for (int row = 0, offset = demandScroll.getCurrentScroll(); row < resourceRows && offset + row < demands.size() + 2; row++) {
            int index = offset + row, y = resourceY + row * RESOURCE_ROW;
            if (row > 0) rect(LEFT_X, y - 2, width, 1, LINE);
            if (index < demands.size()) {
                var value = demands.get(index);
                boolean satisfied = value.occupied() >= value.capacity();
                pair(value.name(), value.amountsText(), LEFT_X, y, width, satisfied ? TEXT : BAD);
                progress(LEFT_X, y + 12, width, 2, fraction(value.occupied(), value.capacity()), satisfied ? GOOD : BAD);
            } else if (index == demands.size()) {
                boolean satisfied = container.energyPaid >= container.energyRequired;
                pair(energyLabel, energyText, LEFT_X, y, width, satisfied ? TEXT : BAD);
                progress(LEFT_X, y + 12, width, 2, fraction(container.energyPaid, container.energyRequired), satisfied ? GOOD : BAD);
            } else pair(availableLabel, availableText, LEFT_X, y, width, TEXT);
        }
        int outputRight = RIGHT_X + COLUMN_WIDTH - 2;
        int hintWidth = fontRenderer.getStringWidth(outputHint);
        text(fontRenderer, outputLabel, RIGHT_X, headerY, COLUMN_WIDTH - hintWidth - 8, TEXT);
        right(fontRenderer, outputHint, outputRight, headerY, hintWidth, SOFT);
        var output = container.outputView.rows();
        int outputWidth = outputWidth();
        if (output.isEmpty())
            centered(fontRenderer, emptyOutput, RIGHT_X, resourceY, COLUMN_WIDTH - 2, resourceRows * RESOURCE_ROW, SOFT);
        for (int row = 0, offset = outputScroll.getCurrentScroll(); row < resourceRows && offset + row < output.size(); row++) {
            var value = output.get(offset + row);
            int y = resourceY + row * RESOURCE_ROW;
            if (row > 0) rect(RIGHT_X, y - 2, outputWidth, 1, LINE);
            pair(value.name(), value.capacityText(), RIGHT_X, y, outputWidth, TEXT);
        }
    }

    private void drawComponents(int mouseX, int mouseY) {
        int width = componentWidth(), left = PANEL_X + 6, right = left + width;
        int stateRight = right - 4, energyRight = stateRight - 46, concurrencyRight = energyRight - 46, countRight = concurrencyRight - 46;
        int headerY = bodyY + 5;
        text(fontRenderer, componentLabel, left + 23, headerY, countRight - 36 - left - 23, SOFT);
        right(fontRenderer, countLabel, countRight, headerY, 36, SOFT);
        right(fontRenderer, concurrencyColumn, concurrencyRight, headerY, 42, SOFT);
        right(fontRenderer, energyColumn, energyRight, headerY, 42, SOFT);
        right(fontRenderer, stateLabel, stateRight, headerY, 42, SOFT);
        boolean running = container.supplyState == ComputerController.SupplyState.ACTIVE;
        String state = running ? runningLabel : pausedLabel;
        int stateWidth = statusWidth(fontRenderer, state);
        for (int row = 0, offset = listScroll.getCurrentScroll(); row < componentRows && offset + row < componentText.size(); row++) {
            var value = componentText.get(offset + row);
            int y = listY + row * COMPONENT_ROW;
            card(left, y, width, COMPONENT_ROW - 1, selectedComponent == offset + row,
                isPointInRegion(left, y, width, COMPONENT_ROW - 1, mouseX, mouseY));
            AEKeyRendering.drawInGui(mc, left + 3, y, container.components.get(offset + row).item());
            text(fontRenderer, value.name, left + 23, y + 4, countRight - 36 - left - 27, TEXT);
            right(fontRenderer, value.count, countRight, y + 4, 36, TEXT);
            right(fontRenderer, value.concurrency, concurrencyRight, y + 4, 42, TEXT);
            right(fontRenderer, value.energy, energyRight, y + 4, 42, TEXT);
            status(fontRenderer, state, stateRight - stateWidth, y + 4, running ? Tone.GOOD : Tone.BAD);
        }
        if (componentText.isEmpty()) {
            centered(fontRenderer, emptyComponents, left, listY, width, componentRows * COMPONENT_ROW, SOFT);
            return;
        }
        var selected = componentText.get(selectedComponent);
        int labelWidth = Math.max(fontRenderer.getStringWidth(requiredLabel), fontRenderer.getStringWidth(providedLabel));
        text(fontRenderer, requiredLabel, LEFT_X, detailY, labelWidth, SOFT);
        text(fontRenderer, providedLabel, LEFT_X, detailY + 12, labelWidth, SOFT);
        text(fontRenderer, selected.required, LEFT_X + labelWidth + 8, detailY, detailTextWidth(labelWidth), TEXT);
        text(fontRenderer, selected.provided, LEFT_X + labelWidth + 8, detailY + 12, detailTextWidth(labelWidth), TEXT);
    }

    private int detailTextWidth(int labelWidth) {
        return PANEL_WIDTH - 16 - labelWidth - 8;
    }

    private int componentAt(int mouseX, int mouseY) {
        if (!isPointInRegion(PANEL_X + 6, listY, componentWidth(), componentRows * COMPONENT_ROW, mouseX, mouseY))
            return -1;
        int index = listScroll.getCurrentScroll() + (mouseY - guiTop - listY) / COMPONENT_ROW;
        return index < componentText.size() ? index : -1;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && page != 0) {
            int index = componentAt(mouseX, mouseY);
            if (index >= 0) {
                selectedComponent = index;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        var tooltip = tooltip(mouseX, mouseY);
        if (tooltip != null) drawHoveringText(tooltip, mouseX, mouseY, fontRenderer);
    }

    private List<String> tooltip(int mouseX, int mouseY) {
        if (isPointInRegion(18, 10, titleWidth(), LINE_HEIGHT, mouseX, mouseY))
            return fits(fontRenderer, titleText, titleWidth()) ? null : List.of(titleText);
        if (shortage() && isPointInRegion(PANEL_X, ALERT_Y, PANEL_WIDTH, ALERT_HEIGHT, mouseX, mouseY))
            return fits(fontRenderer, alertText, PANEL_WIDTH - 30) ? null : List.of(alertText);
        if (page == 0) {
            var demands = container.demandView.rows();
            if (isPointInRegion(LEFT_X, resourceY, demandWidth(), resourceRows * RESOURCE_ROW, mouseX, mouseY)) {
                int index = demandScroll.getCurrentScroll() + (mouseY - guiTop - resourceY) / RESOURCE_ROW;
                return index < demands.size() ? List.of(demands.get(index).text()) : null;
            }
            var output = container.outputView.rows();
            if (isPointInRegion(RIGHT_X, resourceY, outputWidth(), resourceRows * RESOURCE_ROW, mouseX, mouseY)) {
                int index = outputScroll.getCurrentScroll() + (mouseY - guiTop - resourceY) / RESOURCE_ROW;
                return index < output.size() ? List.of(output.get(index).supplyText()) : null;
            }
            return null;
        }
        int index = componentAt(mouseX, mouseY);
        if (index >= 0) return componentText.get(index).tooltip;
        if (!componentText.isEmpty() && isPointInRegion(LEFT_X, detailY, PANEL_WIDTH - 16, DETAIL_HEIGHT, mouseX, mouseY))
            return componentText.get(selectedComponent).tooltip.subList(1, 3);
        return null;
    }

    private record ComponentText(String name, String count, String concurrency, String energy, String required,
                                 String provided, List<String> tooltip) {
    }

    /**
     * Folder tab joined to the page panel; clicking keeps the AE button sound and release handling.
     */
    private final class PageTab extends AE2Button {
        private static final int WIDTH = 62;
        private final int target;
        private final ComputerGuiText label;
        private String title = "";

        private PageTab(int target, ComputerGuiText label) {
            super(0, 0, WIDTH, TAB_HEIGHT, label.text(), () -> selectPage(target));
            this.target = target;
            this.label = label;
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            boolean selected = page == target;
            int top = selected ? y - 1 : y + 1;
            int bottom = selected ? y + height + 1 : y + height;
            rect(x, top, width, bottom - top, LIGHT);
            rect(x + 1, top + 1, width - 2, bottom - top - 1, selected ? PANEL : hovered ? HOVER : SLOT);
            centered(minecraft.fontRenderer, title, x + 4, top + 2, width - 8, LINE_HEIGHT, selected ? TEXT : SOFT);
        }
    }
}
