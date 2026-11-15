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
    private static final int COMPONENT_ROW = 18, RESOURCE_ROW = 18, DETAIL_Y = 202, CONTENT_BOTTOM = 226;
    private final PageTab[] tabs = new PageTab[2];
    private final Scrollbar listScroll = new Scrollbar(), demandScroll = new Scrollbar(), outputScroll = new Scrollbar();
    private int page, selectedComponent, statsY, bodyY, listY, resourceY, componentRows, resourceRows;
    private List<ComputerDetailsPacket.Component> knownComponents;
    private final ObjectArrayList<ComponentText> componentText = new ObjectArrayList<>();
    private NetworkWorkState knownNetwork;
    private ComputerController.SupplyState knownSupply;
    private boolean knownFormed;
    private String stateText, structureText;
    private long knownConcurrency = -1, knownInstalled = -1, knownPaid = -1, knownRequired = -1, knownAvailable = -1;
    private String concurrencyText = "", installedText = "", energyText = "", availableText = "";
    private String titleText, alertText, concurrencyLabel, installedLabel, demandLabel, demandHint, outputLabel;
    private String energyLabel, availableLabel, componentLabel, countLabel, concurrencyColumn, energyColumn, stateLabel;
    private String runningLabel, pausedLabel, emptyComponents, emptyOutput, requiredLabel, providedLabel;

    public GuiComputer(ContainerComputer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 242;
        var labels = new ComputerGuiText[]{ComputerGuiText.Overview, ComputerGuiText.Components};
        for (int index = 0; index < tabs.length; index++) {
            tabs[index] = new PageTab(index, labels[index]);
            widgets.add("page" + index, tabs[index], new Point(18 + index * 62, 0));
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
        stateText = structureText = null;
        statsY = 0;
        knownConcurrency = knownInstalled = knownPaid = knownRequired = knownAvailable = -1;
        titleText = container.getGuiTitle().getFormattedText();
        concurrencyLabel = ComputerGuiText.ConcurrencyLabel.getLocal();
        installedLabel = ComputerGuiText.InstalledLabel.getLocal();
        demandLabel = ComputerGuiText.ComponentResources.getLocal();
        demandHint = ComputerGuiText.AvailableDemand.getLocal();
        outputLabel = ComputerGuiText.OutputResources.getLocal();
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
        for (var tab : tabs) tab.title = tab.label.getLocal();
        container.demandView.invalidateText();
        container.outputView.invalidateText();
    }

    private boolean shortage() {
        return container.supplyState == ComputerController.SupplyState.RESOURCE_SHORTAGE
            || container.supplyState == ComputerController.SupplyState.ENERGY_SHORTAGE;
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        int nextStatsY = shortage() ? 51 : 30;
        if (statsY != nextStatsY) {
            statsY = nextStatsY;
            bodyY = statsY + 56;
            listY = bodyY + 20;
            resourceY = bodyY + 24;
            componentRows = (DETAIL_Y - listY - 3) / COMPONENT_ROW;
            resourceRows = (CONTENT_BOTTOM - resourceY) / RESOURCE_ROW;
            for (var tab : tabs) tab.y = guiTop + bodyY - 14;
            listScroll.setPosition(new Point(359, listY));
            listScroll.setHeight(componentRows * COMPONENT_ROW);
            demandScroll.setPosition(new Point(176, resourceY));
            demandScroll.setHeight(resourceRows * RESOURCE_ROW);
            outputScroll.setPosition(new Point(359, resourceY));
            outputScroll.setHeight(resourceRows * RESOURCE_ROW);
        }
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
                String provided = resources(row.provided()) + ", " + concurrencyLabel
                    + " " + GuiNumbers.grouped(row.concurrency());
                String requiredFull = ComputerGuiText.RequiredPerItem.getLocal(required);
                String providedFull = providedLabel + ": " + provided;
                componentText.add(new ComponentText(name, "×" + GuiNumbers.grouped(row.count()),
                    GuiNumbers.grouped(Math.multiplyExact(row.count(), row.concurrency())),
                    GuiNumbers.grouped(Math.multiplyExact(row.count(), row.energy())), required, provided,
                    List.of(name, requiredFull, providedFull), List.of(requiredFull, providedFull)));
            }
            selectedComponent = Math.clamp(selectedComponent, 0, Math.max(0, componentText.size() - 1));
        }
        if (stateText == null || knownNetwork != container.networkState || knownSupply != container.supplyState) {
            knownNetwork = container.networkState;
            knownSupply = container.supplyState;
            stateText = status().getLocal();
            alertText = (container.supplyState == ComputerController.SupplyState.RESOURCE_SHORTAGE
                ? ComputerGuiText.ResourceShortage : ComputerGuiText.EnergyShortage).getLocal();
        }
        if (structureText == null || knownFormed != container.formed) {
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

    private static String resources(Reference2DoubleMap<NetworkResourceType> values) {
        var labels = new StringBuilder();
        for (var entry : values.reference2DoubleEntrySet()) {
            if (entry.getDoubleValue() != 0) {
                if (!labels.isEmpty()) labels.append(", ");
                labels.append(entry.getKey().displayName()).append(' ').append(entry.getKey().format(entry.getDoubleValue()));
            }
        }
        return labels.isEmpty() ? ComputerGuiText.None.getLocal() : labels.toString();
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, 384, 242);
        if (shortage()) bordered(x + 12, y + 29, 360, 18, Tone.BAD.background, Tone.BAD.border);
        for (int index = 0; index < 3; index++) panel(x + 12 + index * 122, y + statsY, 116, 32);
        panel(x + 12, y + bodyY, 360, 230 - bodyY);
        if (page == 0) rect(x + 192, y + bodyY + 7, 1, 216 - bodyY, LINE);
        else rect(x + 20, y + DETAIL_Y - 3, 344, 1, LINE);
        if (listScroll.isVisible()) GuiAtlas.inset(x + 358, y + listY - 1, 14, componentRows * COMPONENT_ROW + 2);
        if (demandScroll.isVisible()) GuiAtlas.inset(x + 175, y + resourceY - 1, 14, resourceRows * RESOURCE_ROW + 2);
        if (outputScroll.isVisible()) GuiAtlas.inset(x + 358, y + resourceY - 1, 14, resourceRows * RESOURCE_ROW + 2);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        Scrollbar target = null;
        if (page != 0 && isPointInRegion(18, listY, 338, componentRows * COMPONENT_ROW, mouseX, mouseY))
            target = listScroll;
        if (page == 0 && isPointInRegion(20, resourceY, demandScroll.isVisible() ? 151 : 162, resourceRows * RESOURCE_ROW, mouseX, mouseY))
            target = demandScroll;
        if (page == 0 && isPointInRegion(203, resourceY, outputScroll.isVisible() ? 151 : 161, resourceRows * RESOURCE_ROW, mouseX, mouseY))
            target = outputScroll;
        if (target != null) target.setCurrentScroll(target.getCurrentScroll() - Integer.signum(delta));
    }

    private LocalizationEnum status() {
        if (container.networkState == null) return NetworkCenterGuiText.NoState;
        if (container.networkState != NetworkWorkState.ACTIVE_DEVICE) return switch (container.networkState) {
            case UNFORMED -> NetworkCenterGuiText.Unformed;
            case OWNER_TEAM_REJECTED -> NetworkCenterGuiText.TeamRejected;
            case NO_ACTIVE_CENTER -> NetworkCenterGuiText.NoActiveCenter;
            case CENTER_LEVEL_INSUFFICIENT -> NetworkCenterGuiText.LevelInsufficient;
            case INSUFFICIENT_THREADS -> NetworkCenterGuiText.NoConcurrency;
            case INSUFFICIENT_POWER -> NetworkCenterGuiText.NoPower;
            case SAME_TYPE_CONFLICT -> NetworkCenterGuiText.Standby;
            default -> NetworkCenterGuiText.Active;
        };
        return switch (container.supplyState) {
            case ACTIVE -> ComputerGuiText.Running;
            case RESOURCE_SHORTAGE, ENERGY_SHORTAGE -> ComputerGuiText.Stopped;
            case WAITING -> ComputerGuiText.RecipeWaiting;
        };
    }

    private Tone statusTone() {
        if (container.networkState != NetworkWorkState.ACTIVE_DEVICE) return Tone.BAD;
        return switch (container.supplyState) {
            case ACTIVE -> Tone.GOOD;
            case WAITING -> Tone.WAIT;
            case RESOURCE_SHORTAGE, ENERGY_SHORTAGE -> Tone.BAD;
        };
    }

    private void label(String value, int x, int y, int width, int color) {
        text(fontRenderer, value, x, y, width, color);
    }

    private void alignRight(String value, int edge, int y, int width, int color) {
        right(fontRenderer, value, edge, y, width, 10, color);
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        int statusX = 366 - statusWidth(fontRenderer, stateText);
        int structureWidth = Math.min(85, fontRenderer.getStringWidth(structureText));
        int structureX = statusX - structureWidth - 8;
        label(titleText, 16, 12, structureX - 22, TEXT);
        label(structureText, structureX, 12, structureWidth, SOFT);
        DeviceGuiDraw.status(fontRenderer, stateText, statusX, 12, statusTone());
        if (shortage()) {
            Icon.INVALID.getBlitter().dest(18, 30, 16, 16).blit();
            label(alertText, 40, 33, 324, BAD);
        }
        stat(concurrencyLabel, concurrencyText, 20, TEXT);
        stat(installedLabel, installedText, 142, TEXT);
        stat(energyLabel, energyText, 264, container.energyPaid < container.energyRequired ? BAD : TEXT);
        if (page == 0) drawOverview();
        else drawComponents(mouseX, mouseY);
    }

    private void stat(String title, String value, int x, int color) {
        label(title, x, statsY + 3, 100, SOFT);
        GuiLabel.draw(fontRenderer, value, x, statsY + 16, 100, 12, color, false);
    }

    private void pair(String name, String amount, int x, int y, int width, int color) {
        int amountWidth = Math.min(fontRenderer.getStringWidth(amount), width * 3 / 5);
        label(name, x, y, width - amountWidth - 5, TEXT);
        alignRight(amount, x + width, y, amountWidth, color);
    }

    private static double fraction(double available, double required) {
        return required == 0 ? 1 : available / required;
    }

    private void drawOverview() {
        label(demandLabel, 20, bodyY + 7, 84, TEXT);
        right(fontRenderer, demandHint, 182, bodyY + 7, 73, 8, SOFT);
        var demands = container.demandView.rows();
        int width = demandScroll.isVisible() ? 151 : 162;
        for (int row = 0, offset = demandScroll.getCurrentScroll(); row < resourceRows && offset + row < demands.size() + 2; row++) {
            int index = offset + row, y = resourceY + row * RESOURCE_ROW;
            if (row > 0) rect(20, y - 2, width, 1, LINE);
            if (index < demands.size()) {
                var value = demands.get(index);
                boolean satisfied = value.occupied() >= value.capacity();
                pair(value.name(), value.amountsText(), 20, y, width, satisfied ? TEXT : BAD);
                progress(20, y + 12, width, 2, fraction(value.occupied(), value.capacity()), satisfied ? GOOD : BAD);
            } else if (index == demands.size()) {
                boolean satisfied = container.energyPaid >= container.energyRequired;
                pair(energyLabel, energyText, 20, y, width, satisfied ? TEXT : BAD);
                progress(20, y + 12, width, 2, fraction(container.energyPaid, container.energyRequired), satisfied ? GOOD : BAD);
            } else pair(availableLabel, availableText, 20, y, width, TEXT);
        }
        label(outputLabel, 203, bodyY + 7, 140, TEXT);
        right(fontRenderer, "/t", 364, bodyY + 7, 16, 8, SOFT);
        var output = container.outputView.rows();
        int outputWidth = outputScroll.isVisible() ? 151 : 161;
        if (output.isEmpty())
            GuiLabel.drawCentered(fontRenderer, emptyOutput, 203, resourceY, outputWidth,
                resourceRows * RESOURCE_ROW, SOFT, false);
        for (int row = 0, offset = outputScroll.getCurrentScroll(); row < resourceRows && offset + row < output.size(); row++) {
            var value = output.get(offset + row);
            int y = resourceY + row * RESOURCE_ROW;
            if (row > 0) rect(203, y - 2, outputWidth, 1, LINE);
            pair(value.name(), value.capacityText(), 203, y, outputWidth, TEXT);
        }
    }

    private void drawComponents(int mouseX, int mouseY) {
        int headerY = bodyY + 5;
        label(componentLabel, 41, headerY, 116, SOFT);
        alignRight(countLabel, 194, headerY, 32, SOFT);
        alignRight(concurrencyColumn, 244, headerY, 44, SOFT);
        alignRight(energyColumn, 290, headerY, 40, SOFT);
        alignRight(stateLabel, 351, headerY, 45, SOFT);
        boolean running = container.supplyState == ComputerController.SupplyState.ACTIVE;
        String state = running ? runningLabel : pausedLabel;
        int stateWidth = statusWidth(fontRenderer, state);
        for (int row = 0, offset = listScroll.getCurrentScroll(); row < componentRows && offset + row < componentText.size(); row++) {
            var value = componentText.get(offset + row);
            int y = listY + row * COMPONENT_ROW;
            card(18, y, 338, COMPONENT_ROW - 1, selectedComponent == offset + row,
                isPointInRegion(18, y, 338, COMPONENT_ROW - 1, mouseX, mouseY));
            AEKeyRendering.drawInGui(mc, 21, y + 1, container.components.get(offset + row).item());
            label(value.name, 41, y + 3, 116, TEXT);
            alignRight(value.count, 194, y + 3, 32, TEXT);
            alignRight(value.concurrency, 244, y + 3, 44, TEXT);
            alignRight(value.energy, 290, y + 3, 40, TEXT);
            DeviceGuiDraw.status(fontRenderer, state, 351 - stateWidth, y + 3, running ? Tone.GOOD : Tone.BAD);
        }
        if (componentText.isEmpty())
            GuiLabel.drawCentered(fontRenderer, emptyComponents, 20, listY, 337,
                componentRows * COMPONENT_ROW, SOFT, false);
        else {
            var selected = componentText.get(selectedComponent);
            int labelWidth = Math.max(fontRenderer.getStringWidth(requiredLabel), fontRenderer.getStringWidth(providedLabel));
            label(requiredLabel, 20, DETAIL_Y, labelWidth, SOFT);
            label(providedLabel, 20, DETAIL_Y + 12, labelWidth, SOFT);
            label(selected.required, 28 + labelWidth, DETAIL_Y, 336 - labelWidth, TEXT);
            label(selected.provided, 28 + labelWidth, DETAIL_Y + 12, 336 - labelWidth, TEXT);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && page != 0 && isPointInRegion(18, listY, 338, componentRows * COMPONENT_ROW, mouseX, mouseY)) {
            int selected = listScroll.getCurrentScroll() + (mouseY - guiTop - listY) / COMPONENT_ROW;
            if (selected < componentText.size()) selectedComponent = selected;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        List<String> tooltip = null;
        if (page == 0) {
            if (isPointInRegion(20, resourceY, demandScroll.isVisible() ? 151 : 162, resourceRows * RESOURCE_ROW, mouseX, mouseY)) {
                int index = demandScroll.getCurrentScroll() + (mouseY - guiTop - resourceY) / RESOURCE_ROW;
                if (index < container.demandView.rows().size())
                    tooltip = List.of(container.demandView.rows().get(index).text());
            }
            if (isPointInRegion(203, resourceY, outputScroll.isVisible() ? 151 : 161, resourceRows * RESOURCE_ROW, mouseX, mouseY)) {
                int index = outputScroll.getCurrentScroll() + (mouseY - guiTop - resourceY) / RESOURCE_ROW;
                if (index < container.outputView.rows().size())
                    tooltip = List.of(container.outputView.rows().get(index).supplyText());
            }
        } else if (isPointInRegion(18, listY, 338, componentRows * COMPONENT_ROW, mouseX, mouseY)) {
            int index = listScroll.getCurrentScroll() + (mouseY - guiTop - listY) / COMPONENT_ROW;
            if (index < componentText.size()) tooltip = componentText.get(index).tooltip;
        } else if (isPointInRegion(20, DETAIL_Y, 344, 24, mouseX, mouseY) && !componentText.isEmpty())
            tooltip = componentText.get(selectedComponent).details;
        int statusX = 366 - statusWidth(fontRenderer, stateText);
        int structureWidth = Math.min(85, fontRenderer.getStringWidth(structureText));
        int titleWidth = statusX - structureWidth - 30;
        if (isPointInRegion(16, 12, titleWidth, 10, mouseX, mouseY)) {
            if (fontRenderer.getStringWidth(titleText) > titleWidth) tooltip = List.of(titleText);
        } else if (isPointInRegion(statusX, 12, 366 - statusX, 10, mouseX, mouseY)) tooltip = List.of(stateText);
        else if (shortage() && isPointInRegion(12, 29, 360, 18, mouseX, mouseY)) tooltip = List.of(alertText);
        if (tooltip != null) drawHoveringText(tooltip, mouseX, mouseY, fontRenderer);
    }

    private record ComponentText(String name, String count, String concurrency, String energy, String required,
                                 String provided, List<String> tooltip, List<String> details) {
    }

    private final class PageTab extends AE2Button {
        private final int target;
        private final ComputerGuiText label;
        private String title;

        private PageTab(int target, ComputerGuiText label) {
            super(0, 0, 62, 15, label.text(), () -> selectPage(target));
            this.target = target;
            this.label = label;
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            boolean selected = page == target;
            int top = y - (selected ? 1 : 0);
            rect(x, top, width, height + (selected ? 2 : 0), LIGHT);
            rect(x + 1, top + 1, width - 2, height + (selected ? 1 : -1), selected ? PANEL : hovered ? 0xb6baca : SLOT);
            GuiLabel.drawCentered(minecraft.fontRenderer, title, x + 4, top + 2,
                width - 8, 11, selected ? TEXT : SOFT, false);
        }
    }
}
