package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
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
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

public class GuiComputer extends AEBaseGui<ContainerComputer> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_network_center");
    private static final int TEXT = 0x404040, BAD = 0xaa0000, GOOD = 0x006600;
    private final AE2Button[] tabs = new AE2Button[2];
    private final Scrollbar listScroll = new Scrollbar(), demandScroll = new Scrollbar(), outputScroll = new Scrollbar();
    private int page, selectedComponent;
    private List<ComputerDetailsPacket.Component> knownComponents;
    private final ObjectArrayList<ComponentText> componentText = new ObjectArrayList<>();

    public GuiComputer(ContainerComputer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 242;
        var labels = new ComputerGuiText[]{ComputerGuiText.Overview, ComputerGuiText.Components};
        for (int index = 0; index < tabs.length; index++) {
            int target = index;
            tabs[index] = new AE2Button(0, 0, 177, 18, labels[index].text(), () -> selectPage(target));
            widgets.add("page" + index, tabs[index], new Point(12 + index * 183, 99));
        }
        listScroll.setPosition(new Point(359, 137));
        listScroll.setHeight(64);
        demandScroll.setPosition(new Point(176, 155));
        demandScroll.setHeight(68);
        outputScroll.setPosition(new Point(359, 142));
        outputScroll.setHeight(78);
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
    public void initGui() {
        super.initGui();
        knownComponents = null;
        container.demandView.invalidateText();
        container.outputView.invalidateText();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        for (int index = 0; index < tabs.length; index++) tabs[index].setForceHighlighted(page == index);
        listScroll.setRange(0, Math.max(0, container.components.size() - 4), 1);
        listScroll.setVisible(page != 0 && container.components.size() > 4);
        demandScroll.setRange(0, Math.max(0, container.demandView.rows().size() + 2 - 4), 1);
        demandScroll.setVisible(page == 0 && container.demandView.rows().size() + 2 > 4);
        outputScroll.setRange(0, Math.max(0, container.outputView.rows().size() - 6), 1);
        outputScroll.setVisible(page == 0 && container.outputView.rows().size() > 6);
        if (knownComponents != container.components) {
            knownComponents = container.components;
            componentText.clear();
            for (int index1 = 0; index1 < knownComponents.size(); index1++) {
                var row = knownComponents.get(index1);
                componentText.add(new ComponentText(row.item().getDisplayName().getFormattedText(),
                    GuiNumbers.grouped(row.count()), GuiNumbers.grouped(Math.multiplyExact(row.count(), row.concurrency())),
                    GuiNumbers.grouped(Math.multiplyExact(row.count(), row.energy())),
                    ComputerGuiText.RequiredPerItem.getLocal(resources(row.required()) + ", FE " + row.energy() + "/t"),
                    ComputerGuiText.ProvidedPerItem.getLocal(resources(row.provided()) + ", " + row.concurrency())));
            }
            selectedComponent = Math.clamp(selectedComponent, 0, Math.max(0, componentText.size() - 1));
        }
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

    private void panel(int x, int y, int width, int height) {
        Gui.drawRect(x, y, x + width, y + height, 0xff9a9fb4);
        GuiAtlas.inset(x, y, width, height);
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, 384, 242);
        panel(x + 12, y + 36, 360, 31);
        if (page == 0) {
            panel(x + 12, y + 121, 177, 109);
            panel(x + 195, y + 121, 177, 109);
        } else {
            panel(x + 12, y + 118, 360, 112);
            int selected = selectedComponent;
            int offset = listScroll.getCurrentScroll();
            if (!componentText.isEmpty() && selected >= offset && selected < offset + 4)
                Gui.drawRect(x + 19, y + 136 + (selected - offset) * 16, x + 356, y + 152 + (selected - offset) * 16, 0xffadb0c4);
            for (int row = 0; row <= Math.min(4, componentText.size() - offset); row++)
                Gui.drawRect(x + 19, y + 135 + row * 16, x + 355, y + 136 + row * 16, 0xff696d88);
        }
        if (listScroll.isVisible()) GuiAtlas.inset(x + 359, y + 137, 12, 64);
        if (demandScroll.isVisible()) GuiAtlas.inset(x + 176, y + 155, 12, 68);
        if (outputScroll.isVisible()) GuiAtlas.inset(x + 359, y + 142, 12, 78);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        Scrollbar target = null;
        if (page != 0 && isPointInRegion(19, 136, 337, 64, mouseX, mouseY)) target = listScroll;
        if (page == 0 && isPointInRegion(20, 155, 151, 68, mouseX, mouseY)) target = demandScroll;
        if (page == 0 && isPointInRegion(206, 142, 147, 78, mouseX, mouseY)) target = outputScroll;
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

    private void text(String value, int x, int y, int width, int color) {
        GuiLabel.draw(fontRenderer, value, x, y, width, 10, color, false);
    }

    private void right(String value, int right, int y, int width) {
        int textWidth = Math.min(fontRenderer.getStringWidth(value), width);
        text(value, right - textWidth, y, textWidth, TEXT);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        text(container.getGuiTitle().getFormattedText(), 16, 13, 350, TEXT);
        boolean shortage = container.supplyState == ComputerController.SupplyState.RESOURCE_SHORTAGE
            || container.supplyState == ComputerController.SupplyState.ENERGY_SHORTAGE;
        int statusY = shortage ? 43 : 47;
        text(status().getLocal(), 20, statusY, 210, container.supplyState == ComputerController.SupplyState.ACTIVE ? GOOD : BAD);
        if (container.formed) right(MachineGuiText.Formed.getLocal(), 364, statusY, 127);
        if (container.supplyState == ComputerController.SupplyState.RESOURCE_SHORTAGE)
            text(ComputerGuiText.ResourceShortage.getLocal(), 20, 55, 200, TEXT);
        else if (container.supplyState == ComputerController.SupplyState.ENERGY_SHORTAGE)
            text(ComputerGuiText.EnergyShortage.getLocal(), 20, 55, 200, TEXT);
        text(ComputerGuiText.Concurrency.getLocal(GuiNumbers.grouped(container.concurrency)), 20, 79, 180, TEXT);
        text(ComputerGuiText.Installed.getLocal(GuiNumbers.grouped(container.installedCount)), 210, 79, 150, TEXT);
        if (page == 0) drawOverview();
        else drawComponents();
    }

    private void pair(String name, String amount, int x, int y, int width, int color) {
        int amountWidth = Math.min(fontRenderer.getStringWidth(amount), width * 3 / 4);
        text(name, x, y, width - amountWidth - 5, TEXT);
        GuiLabel.draw(fontRenderer, amount, x + width - amountWidth, y, amountWidth, 10, color, false);
    }

    private void drawOverview() {
        text(ComputerGuiText.ComponentResources.getLocal(), 20, 129, 161, TEXT);
        text(ComputerGuiText.AvailableDemand.getLocal(), 20, 143, 161, TEXT);
        var demands = container.demandView.rows();
        int width = demands.size() + 2 > 4 ? 150 : 161;
        for (int row = 0, offset = demandScroll.getCurrentScroll(); row < 4 && offset + row < demands.size() + 2; row++) {
            int index = offset + row, y = 160 + row * 17;
            if (row > 0) Gui.drawRect(20, y - 4, 20 + width, y - 3, 0xff696d88);
            if (index < demands.size()) {
                var value = demands.get(index);
                pair(value.name(), value.amountsText(), 20, y, width, value.occupied() < value.capacity() ? BAD : TEXT);
            } else if (index == demands.size()) {
                pair(ComputerGuiText.EnergyUse.getLocal(), GuiNumbers.grouped(container.energyPaid) + " / " + GuiNumbers.grouped(container.energyRequired),
                    20, y, width, TEXT);
            } else
                pair(ComputerGuiText.EnergyAvailable.getLocal(), GuiNumbers.grouped(container.energyAvailable), 20, y, width, TEXT);
        }
        text(ComputerGuiText.OutputResources.getLocal(), 203, 129, 161, TEXT);
        var output = container.outputView.rows();
        if (output.isEmpty())
            GuiLabel.drawCentered(fontRenderer, NetworkCenterGuiText.NoResources.getLocal(), 203, 169, 161, 10, TEXT, false);
        for (int row = 0, offset = outputScroll.getCurrentScroll(); row < 6 && offset + row < output.size(); row++) {
            var value = output.get(offset + row);
            int y = 148 + row * 13, outputWidth = output.size() > 6 ? 150 : 161;
            if (row > 0) Gui.drawRect(203, y - 3, 203 + outputWidth, y - 2, 0xff696d88);
            pair(value.name(), value.capacityText(), 203, y, outputWidth, TEXT);
        }
    }

    private void drawComponents() {
        text(ComputerGuiText.Component.getLocal(), 20, 123, 147, TEXT);
        right(ComputerGuiText.Count.getLocal(), 208, 123, 34);
        right(ComputerGuiText.ComponentConcurrency.getLocal(), 275, 123, 60);
        right(ComputerGuiText.EnergyPerTick.getLocal(), 326, 123, 45);
        text(ComputerGuiText.State.getLocal(), 331, 123, 24, TEXT);
        boolean running = container.supplyState == ComputerController.SupplyState.ACTIVE;
        for (int row = 0, offset = listScroll.getCurrentScroll(); row < 4 && offset + row < componentText.size(); row++) {
            var value = componentText.get(offset + row);
            int y = 139 + row * 16;
            text(value.name, 20, y, 147, TEXT);
            right(value.count, 208, y, 34);
            right(value.concurrency, 275, y, 60);
            right(value.energy, 326, y, 45);
            text((running ? ComputerGuiText.RecipeRunning : ComputerGuiText.Paused).getLocal(), 331, y, 24, running ? GOOD : BAD);
        }
        if (componentText.isEmpty())
            GuiLabel.drawCentered(fontRenderer, ComputerGuiText.EmptyComponents.getLocal(), 20, 136, 337, 64, TEXT, false);
        else {
            var selected = componentText.get(selectedComponent);
            text(selected.required, 20, 207, 337, TEXT);
            text(selected.provided, 20, 219, 337, TEXT);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && page != 0 && isPointInRegion(19, 136, 337, 64, mouseX, mouseY)) {
            int selected = listScroll.getCurrentScroll() + (mouseY - guiTop - 136) / 16;
            if (selected < componentText.size()) selectedComponent = selected;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        List<String> tooltip = null;
        if (page == 0) {
            if (isPointInRegion(20, 160, 161, 68, mouseX, mouseY)) {
                int index = demandScroll.getCurrentScroll() + (mouseY - guiTop - 160) / 17;
                if (index < container.demandView.rows().size())
                    tooltip = List.of(container.demandView.rows().get(index).text());
            }
            if (tooltip == null)
                tooltip = outputTooltip(mouseX, mouseY);
        } else if (isPointInRegion(19, 136, 337, 64, mouseX, mouseY)) {
            int index = listScroll.getCurrentScroll() + (mouseY - guiTop - 136) / 16;
            if (index < componentText.size()) {
                var value = componentText.get(index);
                tooltip = List.of(value.name, value.required, value.provided);

            }
        } else if (isPointInRegion(19, 204, 337, 24, mouseX, mouseY)) {
            if (!componentText.isEmpty()) {
                var value = componentText.get(selectedComponent);
                tooltip = List.of(value.required, value.provided);

            }
        }
        if (tooltip != null) drawHoveringText(tooltip, mouseX, mouseY, fontRenderer);
    }

    private List<String> outputTooltip(int mouseX, int mouseY) {
        if (!isPointInRegion(203, 148, 161, 78, mouseX, mouseY)) return null;
        int index = outputScroll.getCurrentScroll() + (mouseY - guiTop - 148) / 13;
        var rows = container.outputView.rows();
        return index < rows.size() ? List.of(rows.get(index).supplyText()) : null;
    }

    private record ComponentText(String name, String count, String concurrency, String energy, String required,
                                 String provided) {
    }

}
