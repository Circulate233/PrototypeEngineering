package com.circulation.prototype_eng.client.gui;

import ae2.api.client.AEKeyRendering;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.widgets.AE2Button;
import ae2.client.gui.widgets.Scrollbar;
import ae2.client.gui.widgets.SimpleIconButton;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.container.ContainerResearchStation;
import com.circulation.prototype_eng.container.ResearchStationPacket;
import com.circulation.prototype_eng.localization.ResearchStationGuiText;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

import static com.circulation.prototype_eng.client.gui.DeviceGuiDraw.*;

public class GuiResearchStation extends AEBaseGui<ContainerResearchStation> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_research_station");
    private static final int LIST_X = 18, LIST_Y = 89, LIST_HEIGHT = 180;
    private static final int MATERIAL_COLUMNS = 5, MATERIAL_WIDTH = 32, MATERIAL_HEIGHT = 30;
    private static final int QUEUE_X = 212, QUEUE_Y = 105, QUEUE_ROW = 22, QUEUE_WIDTH = 156;
    private static final String[] QUEUE_NUMBERS = {"1", "2", "3", "4", "5"};
    private final GuiButton[] actions = new GuiButton[4];
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private final DecimalFormat percentFormat = new DecimalFormat("0.#'%'");
    private long selected, knownSelected = -1, knownElapsed = -1;
    private int selectionIndex = -1, knownStations = -1, scaleFactor;
    private ResearchStationPacket.Definition knownDefinition;
    private List<ResearchStationPacket.QueueEntry> knownQueue = List.of();
    private ResearchStationGuiText knownState;
    private String percent = "0%", level = "", workload = "", title = "", queueCount = "0 / 5", statusText = "";
    private String materialsLabel, materialHint, networkLabel, networkHint, queueLabel, emptyResearch, emptyQueue;
    private String noMaterials, noNetworkResources, completedLabel;

    public GuiResearchStation(ContainerResearchStation container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 288;
        actions[0] = new SimpleIconButton(Icon.ARROW_UP, ResearchStationGuiText.Up.text(),
            () -> container.manage(selected, ResearchStationView.Action.UP));
        actions[1] = new SimpleIconButton(Icon.ARROW_DOWN, ResearchStationGuiText.Down.text(),
            () -> container.manage(selected, ResearchStationView.Action.DOWN));
        actions[2] = new AE2Button(0, 0, 58, 18, ResearchStationGuiText.SelectCurrent.text(),
            () -> container.manage(selected, ResearchStationView.Action.CURRENT));
        actions[3] = new AE2Button(0, 0, 58, 18, ResearchStationGuiText.Remove.text(),
            () -> container.manage(selected, ResearchStationView.Action.REMOVE));
        int[] positions = {213, 234, 251, 312};
        for (int index = 0; index < actions.length; index++)
            widgets.add("researchAction" + index, actions[index], new Point(positions[index], 255));
        scrollbar.setPosition(new Point(185, LIST_Y));
        scrollbar.setHeight(LIST_HEIGHT);
        scrollbar.setCaptureMouseWheel(false);
        widgets.add("researchResources", scrollbar);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void initGui() {
        super.initGui();
        container.view.invalidateText();
        knownDefinition = null;
        knownElapsed = -1;
        knownStations = -1;
        knownState = null;
        title = container.getGuiTitle().getFormattedText();
        materialsLabel = ResearchStationGuiText.Resources.getLocal();
        materialHint = ResearchStationGuiText.AvailableRemaining.getLocal();
        networkLabel = ResearchStationGuiText.NetworkResources.getLocal();
        networkHint = ResearchStationGuiText.OccupiedLimit.getLocal();
        queueLabel = ResearchStationGuiText.Queue.getLocal();
        emptyResearch = ResearchStationGuiText.Empty.getLocal();
        emptyQueue = ResearchStationGuiText.EmptyQueue.getLocal();
        noMaterials = ResearchStationGuiText.NoMaterials.getLocal();
        noNetworkResources = ResearchStationGuiText.NoNetworkResources.getLocal();
        completedLabel = ResearchStationGuiText.Completed.getLocal();
        scaleFactor = new ScaledResolution(mc).getScaleFactor();
    }

    private int materialHeight() {
        return Math.max(14, (container.view.materials.size() + MATERIAL_COLUMNS - 1) / MATERIAL_COLUMNS * MATERIAL_HEIGHT);
    }

    private int contentHeight() {
        return 12 + materialHeight() + 8 + 12 + Math.max(14, container.view.resources.size() * 32);
    }

    private int listWidth() {
        return contentHeight() > LIST_HEIGHT ? 162 : 174;
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        var definition = container.view.definition;
        if (knownDefinition != definition) {
            knownDefinition = definition;
            knownElapsed = -1;
            scrollbar.setCurrentScroll(0);
            level = definition == null ? "" : "Lv." + definition.level();
        }
        if (definition != null && (knownElapsed != container.elapsedTicks || knownStations != container.workingStations)) {
            knownElapsed = container.elapsedTicks;
            knownStations = container.workingStations;
            percent = percentFormat.format(100D * container.elapsedTicks / definition.duration());
            workload = ResearchStationGuiText.Workload.getLocal(GuiNumbers.grouped(container.elapsedTicks),
                GuiNumbers.grouped(definition.duration()), GuiNumbers.grouped(container.workingStations));
        }
        var currentState = state();
        if (knownState != currentState) {
            knownState = currentState;
            statusText = currentState.getLocal();
        }
        if (knownQueue != container.view.queue || knownSelected != selected) {
            knownQueue = container.view.queue;
            queueCount = knownQueue.size() + " / 5";
            selectionIndex = -1;
            for (int index = 0; index < knownQueue.size(); index++) {
                if (knownQueue.get(index).entryId() == selected) {
                    selectionIndex = index;
                    break;
                }
            }
            if (selectionIndex < 0 && !knownQueue.isEmpty()) {
                selected = knownQueue.getFirst().entryId();
                selectionIndex = 0;
            }
            knownSelected = selected;
        }
        actions[0].enabled = container.canManage && selectionIndex > 0;
        actions[1].enabled = container.canManage && selectionIndex >= 0 && selectionIndex + 1 < knownQueue.size();
        actions[2].enabled = container.canManage && selectionIndex > 0;
        actions[3].enabled = container.canManage && selectionIndex >= 0;
        int height = contentHeight();
        scrollbar.setRange(0, Math.max(0, height - LIST_HEIGHT), 12);
        scrollbar.setVisible(height > LIST_HEIGHT);
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, xSize, ySize);
        panel(x + 12, y + 29, 360, 48);
        panel(x + 12, y + 82, 186, 194);
        panel(x + 206, y + 82, 166, 194);
        if (contentHeight() > LIST_HEIGHT) rect(x + 184, y + LIST_Y, 9, LIST_HEIGHT, LINE);
    }

    private void text(String value, int x, int y, int width, int color) {
        DeviceGuiDraw.text(fontRenderer, value, x, y, width, color);
    }

    private void right(String value, int right, int y, int width, int color) {
        DeviceGuiDraw.right(fontRenderer, value, right, y, width, 10, color);
    }

    private ResearchStationGuiText state() {
        return !container.visible ? ResearchStationGuiText.Denied : container.manual ? ResearchStationGuiText.Manual
            : container.workingStations > 0 ? ResearchStationGuiText.Running : ResearchStationGuiText.Waiting;
    }

    private Tone stateTone() {
        return !container.visible ? Tone.BAD : container.manual || container.workingStations == 0 ? Tone.WAIT : Tone.GOOD;
    }

    private int researchNameWidth() {
        return 366 - statusWidth(fontRenderer, statusText) - fontRenderer.getStringWidth(level) - 58;
    }

    private int queueNameWidth(int index) {
        return QUEUE_WIDTH - fontRenderer.getStringWidth(container.view.queueLevel(index)) - 24;
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        int titleWidth = Math.min(200, fontRenderer.getStringWidth(title));
        text(title, 18, 12, titleWidth, TEXT);
        text(container.teamName, 18 + titleWidth + 8, 12, 340 - titleWidth, SOFT);
        var definition = container.view.definition;
        if (definition == null)
            GuiLabel.drawCentered(fontRenderer, emptyResearch, 18, 35, 348, 36, SOFT, false);
        else {
            slot(18, 36);
            if (definition.icon() != null) AEKeyRendering.drawInGui(mc, 19, 37, definition.icon());
            int nameWidth = researchNameWidth();
            text(container.view.name(), 42, 36, nameWidth, TEXT);
            int levelX = 42 + Math.min(nameWidth, fontRenderer.getStringWidth(container.view.name())) + 5;
            int levelWidth = fontRenderer.getStringWidth(level) + 6;
            rect(levelX, 36, levelWidth, 10, LINE);
            GuiLabel.drawCentered(fontRenderer, level, levelX, 36, levelWidth, 10, LIGHT, false);
            status(fontRenderer, statusText, 366 - statusWidth(fontRenderer, statusText), 36, stateTone());
            text(workload, 42, 48, 324, SOFT);
            progress(18, 64, 312, 5, (double) container.elapsedTicks / definition.duration(),
                container.elapsedTicks >= definition.duration() ? GOOD : DARK);
            right(percent, 366, 61, 30, TEXT);
        }
        text(queueLabel, QUEUE_X, 90, 110, TEXT);
        right(queueCount, 368, 90, 40, SOFT);
        for (int index = 0; index < container.view.queue.size(); index++) {
            int top = QUEUE_Y + index * QUEUE_ROW;
            boolean hover = isPointInRegion(QUEUE_X, top, QUEUE_WIDTH, 20, mouseX, mouseY);
            card(QUEUE_X, top, QUEUE_WIDTH, 20, container.view.queue.get(index).entryId() == selected, hover);
            if (index == 0) rect(QUEUE_X, top + 1, 2, 18, GOOD);
            text(QUEUE_NUMBERS[index], QUEUE_X + 4, top + 5, 10, SOFT);
            text(container.view.queueName(index), QUEUE_X + 18, top + 5, queueNameWidth(index), TEXT);
            right(container.view.queueLevel(index), 364, top + 5, QUEUE_WIDTH - 22, index == 0 ? GOOD : SOFT);
        }
        if (container.view.queue.isEmpty())
            GuiLabel.drawCentered(fontRenderer, emptyQueue, QUEUE_X, QUEUE_Y, QUEUE_WIDTH, 110, SOFT, false);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor((guiLeft + LIST_X) * scaleFactor, mc.displayHeight - (guiTop + LIST_Y + LIST_HEIGHT) * scaleFactor,
                listWidth() * scaleFactor, LIST_HEIGHT * scaleFactor);
            drawResources();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void drawResources() {
        int top = LIST_Y - scrollbar.getCurrentScroll(), width = listWidth();
        text(materialsLabel, LIST_X, top, 34, TEXT);
        right(materialHint, LIST_X + width, top, width - 38, SOFT);
        top += 12;
        int firstMaterial = Math.min(container.view.materials.size(),
            Math.max(0, Math.floorDiv(LIST_Y - top, MATERIAL_HEIGHT)) * MATERIAL_COLUMNS);
        int lastMaterial = Math.min(container.view.materials.size(),
            (LIST_Y + LIST_HEIGHT - top + MATERIAL_HEIGHT - 1) / MATERIAL_HEIGHT * MATERIAL_COLUMNS);
        for (int index = firstMaterial; index < lastMaterial; index++) {
            var material = container.view.materials.get(index);
            int cellX = LIST_X + index % MATERIAL_COLUMNS * MATERIAL_WIDTH;
            int cellY = top + index / MATERIAL_COLUMNS * MATERIAL_HEIGHT;
            slot(cellX + 7, cellY);
            AEKeyRendering.drawInGui(mc, cellX + 8, cellY + 1, material.key);
            GuiLabel.drawCentered(fontRenderer, material.slotAmounts(), cellX, cellY + 20, MATERIAL_WIDTH, 7,
                material.available < material.remaining ? BAD : TEXT, false);
        }
        if (container.view.materials.isEmpty())
            text(noMaterials, LIST_X, top + 2, width, SOFT);
        top += materialHeight() + 8;
        text(networkLabel, LIST_X, top, 60, TEXT);
        right(networkHint, LIST_X + width, top, width - 64, SOFT);
        top += 12;
        int firstResource = Math.clamp(Math.floorDiv(LIST_Y - top, 32), 0, container.view.resources.size());
        int lastResource = Math.clamp(Math.floorDiv(LIST_Y + LIST_HEIGHT - top + 31, 32), 0, container.view.resources.size());
        for (int index = firstResource; index < lastResource; index++) {
            int rowY = top + index * 32;
            var resource = container.view.resources.get(index);
            boolean completed = resource.accumulated >= resource.total;
            String value = completed ? completedLabel : resource.amounts();
            int valueWidth = Math.min(width / 2, fontRenderer.getStringWidth(value));
            text(resource.name(), LIST_X, rowY + 2, width - valueWidth - 6, TEXT);
            right(value, LIST_X + width, rowY + 2, valueWidth, completed ? GOOD : TEXT);
            progress(LIST_X, rowY + 15, width, 2, resource.accumulated / resource.total, completed ? GOOD : DARK);
            text(resource.progress(), LIST_X, rowY + 19, width - 35, SOFT);
            right(resource.percentage(), LIST_X + width, rowY + 19, 30, SOFT);
            if (index + 1 < container.view.resources.size()) rect(LIST_X, rowY + 31, width, 1, LINE);
        }
        if (container.view.resources.isEmpty())
            text(noNetworkResources, LIST_X, top + 2, width, SOFT);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth, mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && isPointInRegion(LIST_X, LIST_Y, 176, LIST_HEIGHT, mouseX, mouseY))
            scrollbar.setCurrentScroll(Math.clamp(scrollbar.getCurrentScroll() - Integer.signum(delta) * 17L, 0,
                Math.max(0, contentHeight() - LIST_HEIGHT)));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && isPointInRegion(QUEUE_X, QUEUE_Y, QUEUE_WIDTH, container.view.queue.size() * QUEUE_ROW, mouseX, mouseY)) {
            int rowY = mouseY - guiTop - QUEUE_Y;
            if (rowY % QUEUE_ROW < 20) selected = container.view.queue.get(rowY / QUEUE_ROW).entryId();
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        int titleWidth = Math.min(200, fontRenderer.getStringWidth(title));
        if (isPointInRegion(18, 12, titleWidth, 10, mouseX, mouseY) && fontRenderer.getStringWidth(title) > titleWidth)
            drawHoveringText(List.of(title), mouseX, mouseY, fontRenderer);
        if (isPointInRegion(18 + titleWidth + 8, 12, 340 - titleWidth, 10, mouseX, mouseY)
            && fontRenderer.getStringWidth(container.teamName) > 340 - titleWidth)
            drawHoveringText(List.of(container.teamName), mouseX, mouseY, fontRenderer);
        if (container.view.definition != null && isPointInRegion(42, 48, 324, 10, mouseX, mouseY)
            && fontRenderer.getStringWidth(workload) > 324)
            drawHoveringText(List.of(workload), mouseX, mouseY, fontRenderer);
        if (container.view.definition != null && isPointInRegion(42, 36, researchNameWidth(), 10, mouseX, mouseY)
            && fontRenderer.getStringWidth(container.view.name()) > researchNameWidth())
            drawHoveringText(List.of(container.view.name()), mouseX, mouseY, fontRenderer);
        if (isPointInRegion(QUEUE_X + 18, QUEUE_Y, QUEUE_WIDTH - 18, container.view.queue.size() * QUEUE_ROW, mouseX, mouseY)) {
            int rowY = mouseY - guiTop - QUEUE_Y;
            int index = rowY / QUEUE_ROW;
            if (rowY % QUEUE_ROW < 20 && fontRenderer.getStringWidth(container.view.queueName(index)) > queueNameWidth(index))
                drawHoveringText(List.of(container.view.queueName(index)), mouseX, mouseY, fontRenderer);
        }
        if (!isPointInRegion(LIST_X, LIST_Y, listWidth(), LIST_HEIGHT, mouseX, mouseY)) return;
        int rowY = mouseY - guiTop - LIST_Y + scrollbar.getCurrentScroll() - 12;
        if (rowY >= 0 && rowY < materialHeight()) {
            int column = (mouseX - guiLeft - LIST_X) / MATERIAL_WIDTH;
            int index = rowY / MATERIAL_HEIGHT * MATERIAL_COLUMNS + column;
            if (column < MATERIAL_COLUMNS && index < container.view.materials.size() && rowY % MATERIAL_HEIGHT < 27) {
                var material = container.view.materials.get(index);
                drawHoveringText(List.of(material.name(), material.amounts()), mouseX, mouseY, fontRenderer);
            }
            return;
        }
        rowY -= materialHeight() + 20;
        if (rowY >= 0 && rowY < container.view.resources.size() * 32) {
            var resource = container.view.resources.get(rowY / 32);
            drawHoveringText(List.of(resource.name(), resource.amounts(), resource.progress()), mouseX, mouseY, fontRenderer);
        }
    }
}
