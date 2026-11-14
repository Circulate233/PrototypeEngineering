package com.circulation.prototype_eng.client.gui;

import ae2.api.stacks.AmountFormat;
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
import com.circulation.prototype_eng.localization.NetworkCenterGuiText;
import com.circulation.prototype_eng.localization.MachineGuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.lwjgl.input.Mouse;

public class GuiComputer extends AEBaseGui<ContainerComputer> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_network_center");
    private static final int TEXT = 0x404040, BAD = 0xaa0000, GOOD = 0x006600;
    private final AE2Button[] tabs = new AE2Button[3];
    private final Scrollbar listScroll = new Scrollbar(), demandScroll = new Scrollbar(), outputScroll = new Scrollbar();
    private int page, selectedComponent, selectedRecipe;
    private List<ComputerDetailsPacket.Component> knownComponents;
    private List<ComputerDetailsPacket.Recipe> knownRecipes;
    private final ObjectArrayList<ComponentText> componentText = new ObjectArrayList<>();
    private final ObjectArrayList<RecipeText> recipeText = new ObjectArrayList<>();

    public GuiComputer(ContainerComputer container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 242;
        var labels = new ComputerGuiText[]{ComputerGuiText.Overview, ComputerGuiText.Components, ComputerGuiText.Recipes};
        for (int index = 0; index < tabs.length; index++) {
            int target = index;
            tabs[index] = new AE2Button(0, 0, 117, 18, labels[index].text(), () -> selectPage(target));
            widgets.add("page" + index, tabs[index], new Point(12 + index * 121, 99));
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
        knownRecipes = null;
        container.demandView.invalidateText();
        container.outputView.invalidateText();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        for (int index = 0; index < tabs.length; index++) tabs[index].setForceHighlighted(page == index);
        listScroll.setRange(0, Math.max(0, (page == 1 ? container.components.size() : container.recipes.size()) - 4), 1);
        listScroll.setVisible(page != 0 && (page == 1 ? container.components.size() : container.recipes.size()) > 4);
        demandScroll.setRange(0, Math.max(0, container.demandView.rows().size() + 2 - 4), 1);
        demandScroll.setVisible(page == 0 && container.demandView.rows().size() + 2 > 4);
        outputScroll.setRange(0, Math.max(0, container.outputView.rows().size() - 6), 1);
        outputScroll.setVisible(page == 0 && container.outputView.rows().size() > 6);
        if (knownComponents != container.components) {
            knownComponents = container.components;
            componentText.clear();
            for (var row : knownComponents) {
                componentText.add(new ComponentText(row.item().getDisplayName().getFormattedText(),
                    GuiNumbers.grouped(row.count()), GuiNumbers.grouped(Math.multiplyExact(row.count(), row.concurrency())),
                    GuiNumbers.grouped(Math.multiplyExact(row.count(), row.energy())),
                    ComputerGuiText.RequiredPerItem.getLocal(resources(row.required()) + ", FE " + row.energy() + "/t"),
                    ComputerGuiText.ProvidedPerItem.getLocal(resources(row.provided()) + ", " + row.concurrency())));
            }
            selectedComponent = Math.clamp(selectedComponent, 0, Math.max(0, componentText.size() - 1));
        }
        if (knownRecipes != container.recipes) {
            knownRecipes = container.recipes;
            recipeText.clear();
            for (var row : knownRecipes) {
                var inputs = new ObjectArrayList<String>(row.inputs().size());
                for (var input : row.inputs())
                    inputs.add(input.what().getDisplayName().getFormattedText() + " " + input.what().formatAmount(input.amount(), AmountFormat.FULL));
                if (!row.tickInputs().isEmpty()) {
                    var ticks = new ObjectArrayList<String>();
                    for (var input : row.tickInputs())
                        ticks.add(input.what().getDisplayName().getFormattedText() + " " + input.what().formatAmount(input.amount(), AmountFormat.FULL));
                    inputs.add(ComputerGuiText.TickInputs.getLocal(String.join(", ", ticks)));
                }
                if (!row.endInputs().isEmpty()) {
                    var ends = new ObjectArrayList<String>();
                    for (var input : row.endInputs())
                        ends.add(input.what().getDisplayName().getFormattedText() + " " + input.what().formatAmount(input.amount(), AmountFormat.FULL));
                    inputs.add(ComputerGuiText.EndInputs.getLocal(String.join(", ", ends)));
                }
                recipeText.add(new RecipeText(row.id().getPath(), String.join(", ", inputs), row.duration() < 0 ? resources(row.provided()) : ""));
            }
            selectedRecipe = Math.clamp(selectedRecipe, 0, Math.max(0, recipeText.size() - 1));
        }
    }

    private static String resources(Reference2DoubleMap<NetworkResourceType> values) {
        var labels = new ObjectArrayList<String>(values.size());
        for (var entry : values.reference2DoubleEntrySet()) {
            if (entry.getDoubleValue() != 0)
                labels.add(entry.getKey().displayName() + " " + entry.getKey().format(entry.getDoubleValue()));
        }
        return labels.isEmpty() ? ComputerGuiText.None.getLocal() : String.join(", ", labels);
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
            int selected = page == 1 ? selectedComponent : selectedRecipe;
            int offset = listScroll.getCurrentScroll();
            if (selected >= offset && selected < offset + 4)
                Gui.drawRect(x + 19, y + 136 + (selected - offset) * 16, x + 356, y + 152 + (selected - offset) * 16, 0xffadb0c4);
            for (int row = 0; row < 5; row++)
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
        fontRenderer.drawString(fontRenderer.trimStringToWidth(value, width), x, y, color);
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        text(Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 16, 13, 350, TEXT);
        text(status().getLocal(), 20, 43, 210, container.supplyState == ComputerController.SupplyState.ACTIVE ? GOOD : BAD);
        text(ComputerGuiText.StructureNetwork.getLocal((container.formed ? MachineGuiText.Formed : MachineGuiText.NotFormed).getLocal(),
            (container.networkState == NetworkWorkState.ACTIVE_DEVICE ? NetworkCenterGuiText.Active : status()).getLocal()), 237, 51, 127, TEXT);
        if (container.supplyState == ComputerController.SupplyState.RESOURCE_SHORTAGE)
            text(ComputerGuiText.ResourceShortage.getLocal(), 20, 55, 200, TEXT);
        else if (container.supplyState == ComputerController.SupplyState.ENERGY_SHORTAGE)
            text(ComputerGuiText.EnergyShortage.getLocal(), 20, 55, 200, TEXT);
        text(ComputerGuiText.Concurrency.getLocal(GuiNumbers.grouped(container.concurrency),
            container.concurrencyLimit == Long.MAX_VALUE ? "∞" : GuiNumbers.grouped(container.concurrencyLimit)), 20, 79, 180, TEXT);
        text(ComputerGuiText.Installed.getLocal(GuiNumbers.grouped(container.installedCount)), 210, 79, 150, TEXT);
        if (page == 0) drawOverview();
        else if (page == 1) drawComponents();
        else drawRecipes();
    }

    private void pair(String name, String amount, int x, int y, int width, int color) {
        int amountWidth = Math.min(fontRenderer.getStringWidth(amount), width * 3 / 4);
        text(name, x, y, width - amountWidth - 5, TEXT);
        GuiLabel.draw(fontRenderer, amount, x + width - amountWidth, y, amountWidth, 9, color, false);
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
        text(ComputerGuiText.Count.getLocal(), 174, 123, 30, TEXT);
        text(ComputerGuiText.ComponentConcurrency.getLocal(), 215, 123, 66, TEXT);
        text(ComputerGuiText.EnergyPerTick.getLocal(), 281, 123, 45, TEXT);
        text(ComputerGuiText.State.getLocal(), 331, 123, 30, TEXT);
        boolean running = container.supplyState == ComputerController.SupplyState.ACTIVE;
        for (int row = 0, offset = listScroll.getCurrentScroll(); row < 4 && offset + row < componentText.size(); row++) {
            var value = componentText.get(offset + row);
            int y = 140 + row * 16;
            text(value.name, 20, y, 147, TEXT);
            text(value.count, 174, y, 34, TEXT);
            text(value.concurrency, 215, y, 60, TEXT);
            text(value.energy, 281, y, 45, TEXT);
            text((running ? ComputerGuiText.RecipeRunning : ComputerGuiText.Paused).getLocal(), 331, y, 24, running ? GOOD : BAD);
        }
        if (componentText.isEmpty()) text(ComputerGuiText.EmptyComponents.getLocal(), 20, 145, 325, TEXT);
        else {
            var selected = componentText.get(selectedComponent);
            text(selected.required, 20, 207, 337, TEXT);
            text(selected.provided, 20, 219, 337, TEXT);
        }
    }

    private ComputerGuiText recipeState(int index) {
        return switch (container.recipeStates[index]) {
            case ComputerController.RECIPE_ACTIVE -> ComputerGuiText.RecipeRunning;
            case ComputerController.RECIPE_CONDITION -> ComputerGuiText.RecipeCondition;
            case ComputerController.RECIPE_INPUT -> ComputerGuiText.RecipeInput;
            case ComputerController.RECIPE_PARTIAL -> ComputerGuiText.Partial;
            case ComputerController.RECIPE_FAILED -> ComputerGuiText.Failed;
            case ComputerController.RECIPE_WAITING -> ComputerGuiText.RecipeWaiting;
            default ->
                throw new IllegalStateException("Unknown computer recipe state: " + container.recipeStates[index]);
        };
    }

    private void drawRecipes() {
        text(ComputerGuiText.Recipe.getLocal(), 20, 123, 82, TEXT);
        text(ComputerGuiText.Inputs.getLocal(), 109, 123, 97, TEXT);
        text(ComputerGuiText.OutputsProgress.getLocal(), 210, 123, 96, TEXT);
        text(ComputerGuiText.State.getLocal(), 313, 123, 42, TEXT);
        for (int row = 0, offset = listScroll.getCurrentScroll(); row < 4 && offset + row < recipeText.size(); row++) {
            int index = offset + row, y = 140 + row * 16;
            var value = recipeText.get(index);
            text(value.name, 20, y, 82, TEXT);
            text(value.inputs, 109, y, 97, TEXT);
            text(container.recipes.get(index).duration() < 0 ? value.provided : GuiNumbers.grouped((long) container.recipeProgress[index]) + " / " + container.recipes.get(index).duration(), 210, y, 96, TEXT);
            text(recipeState(index).getLocal(), 313, y, 42, container.recipeStates[index] == ComputerController.RECIPE_ACTIVE ? GOOD : BAD);
        }
        if (recipeText.isEmpty()) text(ComputerGuiText.EmptyRecipes.getLocal(), 20, 145, 325, TEXT);
        else {
            var selected = recipeText.get(selectedRecipe);
            text(selected.inputs, 20, 207, 337, TEXT);
            text(selected.provided, 20, 219, 337, TEXT);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && page != 0 && isPointInRegion(19, 136, 337, 64, mouseX, mouseY)) {
            int selected = listScroll.getCurrentScroll() + (mouseY - guiTop - 136) / 16;
            if (page == 1 && selected < componentText.size()) selectedComponent = selected;
            if (page == 2 && selected < recipeText.size()) selectedRecipe = selected;
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
            if (page == 1 && index < componentText.size()) {
                var value = componentText.get(index);
                tooltip = List.of(value.name, value.required, value.provided);
            } else if (page == 2 && index < recipeText.size()) {
                var value = recipeText.get(index);
                tooltip = List.of(value.name, value.inputs, value.provided, recipeState(index).getLocal());
            }
        } else if (isPointInRegion(19, 204, 337, 24, mouseX, mouseY)) {
            if (page == 1 && !componentText.isEmpty()) {
                var value = componentText.get(selectedComponent);
                tooltip = List.of(value.required, value.provided);
            } else if (page == 2 && !recipeText.isEmpty()) {
                var value = recipeText.get(selectedRecipe);
                tooltip = List.of(value.inputs, value.provided);
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

    private record RecipeText(String name, String inputs, String provided) {
    }
}
