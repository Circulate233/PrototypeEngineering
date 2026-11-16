package com.circulation.prototype_eng.client.gui;

import ae2.api.client.AEKeyRendering;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.widgets.AE2Button;
import ae2.client.gui.widgets.Scrollbar;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.container.ContainerResearchStation;
import com.circulation.prototype_eng.container.ResearchStationPacket;
import com.circulation.prototype_eng.localization.ResearchStationGuiText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

import static com.circulation.prototype_eng.client.gui.DeviceGuiDraw.*;

@SideOnly(Side.CLIENT)
public class GuiResearchStation extends AEBaseGui<ContainerResearchStation> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_research_station");
    private static final int HERO_X = 12, HERO_Y = 28, HERO_WIDTH = 360, HERO_HEIGHT = 50;
    private static final int LIST_X = 18, LIST_Y = 90, LIST_HEIGHT = 180, LIST_SCROLL_X = 185;
    private static final int MATERIAL_COLUMNS = 5, MATERIAL_WIDTH = 33, MATERIAL_HEIGHT = 30, RESOURCE_HEIGHT = 32;
    private static final int SECTION_HEIGHT = 12, SECTION_GAP = 8, EMPTY_HEIGHT = 14;
    private static final int QUEUE_X = 212, QUEUE_Y = 104, QUEUE_ROW = 22, QUEUE_CARD = 20, QUEUE_WIDTH = 154, QUEUE_SIZE = 5;
    private static final int ACTION_Y = 258, ACTION_HEIGHT = 13;
    private static final String[] QUEUE_NUMBERS = {"1", "2", "3", "4", "5"};
    private final QueueButton[] actions = new QueueButton[4];
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private final DecimalFormat percentFormat = new DecimalFormat("0.#'%'");
    private long selected, knownElapsed = -1;
    private int knownStations = -1, scaleFactor;
    private ResearchStationPacket.Definition knownDefinition;
    private List<ResearchStationPacket.QueueEntry> knownQueue;
    private ResearchStationGuiText knownState;
    private String percent = "", level = "", workload = "", queueCount = "", statusText = "";
    private String title, materialsLabel, materialHint, networkLabel, networkHint, queueLabel, emptyResearch, denied, emptyQueue;
    private String noMaterials, noNetworkResources, completedLabel;

    public GuiResearchStation(ContainerResearchStation container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 288;
        actions[0] = new QueueButton(17, Icon.ARROW_UP, ResearchStationGuiText.Up.text(),
            () -> container.manage(selected, ResearchStationView.Action.UP));
        actions[1] = new QueueButton(17, Icon.ARROW_DOWN, ResearchStationGuiText.Down.text(),
            () -> container.manage(selected, ResearchStationView.Action.DOWN));
        actions[2] = new QueueButton(56, null, ResearchStationGuiText.SelectCurrent.text(),
            () -> container.manage(selected, ResearchStationView.Action.CURRENT));
        actions[3] = new QueueButton(55, null, ResearchStationGuiText.Remove.text(),
            () -> container.manage(selected, ResearchStationView.Action.REMOVE));
        widgets.add("researchUp", actions[0], new Point(QUEUE_X, ACTION_Y));
        widgets.add("researchDown", actions[1], new Point(QUEUE_X + 20, ACTION_Y));
        widgets.add("researchCurrent", actions[2], new Point(QUEUE_X + 40, ACTION_Y));
        widgets.add("researchRemove", actions[3], new Point(QUEUE_X + 99, ACTION_Y));
        scrollbar.setPosition(new Point(LIST_SCROLL_X, LIST_Y));
        scrollbar.setHeight(LIST_HEIGHT);
        scrollbar.setCaptureMouseWheel(false);
        widgets.add("researchResources", scrollbar);
    }

    @Override
    @SuppressWarnings({"DataFlowIssue", "ForLoopReplaceableByForEach"})
    public void initGui() {
        super.initGui();
        container.view.invalidateText();
        knownDefinition = null;
        knownQueue = null;
        knownState = null;
        knownElapsed = -1;
        knownStations = -1;
        title = container.getGuiTitle().getFormattedText();
        materialsLabel = ResearchStationGuiText.Resources.getLocal();
        materialHint = ResearchStationGuiText.AvailableRemaining.getLocal();
        networkLabel = ResearchStationGuiText.NetworkResources.getLocal();
        networkHint = ResearchStationGuiText.OccupiedLimit.getLocal();
        queueLabel = ResearchStationGuiText.Queue.getLocal();
        emptyResearch = ResearchStationGuiText.Empty.getLocal();
        denied = ResearchStationGuiText.Denied.getLocal();
        emptyQueue = ResearchStationGuiText.EmptyQueue.getLocal();
        noMaterials = ResearchStationGuiText.NoMaterials.getLocal();
        noNetworkResources = ResearchStationGuiText.NoNetworkResources.getLocal();
        completedLabel = ResearchStationGuiText.Completed.getLocal();
        for (int index = 0; index < actions.length; index++)
            actions[index].label = actions[index].getMessageComponent().getFormattedText();
        scaleFactor = new ScaledResolution(mc).getScaleFactor();
    }

    private int materialRows() {
        return (container.view.materials.size() + MATERIAL_COLUMNS - 1) / MATERIAL_COLUMNS;
    }

    private int materialHeight() {
        return container.view.materials.isEmpty() ? EMPTY_HEIGHT : materialRows() * MATERIAL_HEIGHT;
    }

    private int resourceHeight() {
        return container.view.resources.isEmpty() ? EMPTY_HEIGHT : container.view.resources.size() * RESOURCE_HEIGHT;
    }

    private int resourcesOffset() {
        return SECTION_HEIGHT + materialHeight() + SECTION_GAP + SECTION_HEIGHT;
    }

    private int contentHeight() {
        return resourcesOffset() + resourceHeight();
    }

    private int listWidth() {
        return contentHeight() > LIST_HEIGHT ? 164 : 174;
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
            percent = percentFormat.format(100D * Math.min(container.elapsedTicks, definition.duration()) / definition.duration());
            workload = ResearchStationGuiText.Workload.getLocal(GuiNumbers.grouped(container.elapsedTicks),
                GuiNumbers.grouped(definition.duration()), GuiNumbers.grouped(container.workingStations));
        }
        var currentState = state();
        if (knownState != currentState) {
            knownState = currentState;
            statusText = currentState.getLocal();
        }
        if (knownQueue != container.view.queue) {
            knownQueue = container.view.queue;
            queueCount = knownQueue.size() + " / " + QUEUE_SIZE;
        }
        int selectionIndex = -1;
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
        panel(x + HERO_X, y + HERO_Y, HERO_WIDTH, HERO_HEIGHT);
        panel(x + 12, y + 82, 186, 194);
        panel(x + 206, y + 82, 166, 194);
        if (scrollbar.isVisible()) GuiAtlas.inset(x + LIST_SCROLL_X - 1, y + LIST_Y - 1, 9, LIST_HEIGHT + 2);
    }

    private ResearchStationGuiText state() {
        return !container.visible ? ResearchStationGuiText.Denied : container.manual ? ResearchStationGuiText.Manual
            : container.workingStations > 0 ? ResearchStationGuiText.Running : ResearchStationGuiText.Waiting;
    }

    private Tone stateTone() {
        return !container.visible ? Tone.BAD : container.manual || container.workingStations == 0 ? Tone.WAIT : Tone.GOOD;
    }

    private int titleWidth() {
        return Math.min(220, fontRenderer.getStringWidth(title));
    }

    private int teamX() {
        return 18 + titleWidth() + 8;
    }

    private int statusX() {
        return 366 - statusWidth(fontRenderer, statusText);
    }

    private int levelWidth() {
        return fontRenderer.getStringWidth(level) + 6;
    }

    private int researchNameWidth() {
        return statusX() - 42 - levelWidth() - 12;
    }

    private int queueNameWidth(int index) {
        return QUEUE_WIDTH - 24 - fontRenderer.getStringWidth(container.view.queueLevel(index)) - 6;
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        text(fontRenderer, title, 18, 11, titleWidth(), TEXT);
        text(fontRenderer, container.teamName, teamX(), 11, 366 - teamX(), SOFT);
        drawHero();
        drawQueue(mouseX, mouseY);
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

    private void drawHero() {
        var definition = container.view.definition;
        if (definition == null) {
            centered(fontRenderer, container.visible ? emptyResearch : denied, HERO_X, HERO_Y, HERO_WIDTH, HERO_HEIGHT, SOFT);
            return;
        }
        slot(18, 34);
        if (definition.icon() != null) AEKeyRendering.drawInGui(mc, 19, 35, definition.icon());
        String name = container.view.name();
        int nameWidth = Math.min(researchNameWidth(), fontRenderer.getStringWidth(name));
        text(fontRenderer, name, 42, 35, nameWidth, TEXT);
        int levelX = 42 + nameWidth + 5;
        rect(levelX, 35, levelWidth(), LINE_HEIGHT, LINE);
        fontRenderer.drawString(level, levelX + 3, 36, LIGHT);
        status(fontRenderer, statusText, statusX(), 35, stateTone());
        text(fontRenderer, workload, 42, 46, 324, SOFT);
        boolean finished = container.elapsedTicks >= definition.duration();
        progress(18, 63, 310, 6, (double) container.elapsedTicks / definition.duration(), finished ? GOOD : DARK);
        right(fontRenderer, percent, 366, 61, 34, TEXT);
    }

    private void drawQueue(int mouseX, int mouseY) {
        text(fontRenderer, queueLabel, QUEUE_X, 89, 110, TEXT);
        right(fontRenderer, queueCount, 366, 89, 40, SOFT);
        var queue = container.view.queue;
        for (int index = 0; index < queue.size(); index++) {
            int top = QUEUE_Y + index * QUEUE_ROW;
            card(QUEUE_X, top, QUEUE_WIDTH, QUEUE_CARD, queue.get(index).entryId() == selected,
                isPointInRegion(QUEUE_X, top, QUEUE_WIDTH, QUEUE_CARD, mouseX, mouseY));
            if (index == 0) rect(QUEUE_X, top, 2, QUEUE_CARD, GOOD);
            fontRenderer.drawString(QUEUE_NUMBERS[index], QUEUE_X + 6, top + 6, SOFT);
            text(fontRenderer, container.view.queueName(index), QUEUE_X + 18, top + 5, queueNameWidth(index), TEXT);
            right(fontRenderer, container.view.queueLevel(index), QUEUE_X + QUEUE_WIDTH - 4, top + 5,
                QUEUE_WIDTH - 22, index == 0 ? GOOD : SOFT);
        }
        if (queue.isEmpty())
            centered(fontRenderer, container.visible ? emptyQueue : denied, QUEUE_X, QUEUE_Y, QUEUE_WIDTH, QUEUE_SIZE * QUEUE_ROW, SOFT);
    }

    private void drawResources() {
        int width = listWidth(), top = LIST_Y - scrollbar.getCurrentScroll();
        text(fontRenderer, materialsLabel, LIST_X, top, 40, TEXT);
        right(fontRenderer, materialHint, LIST_X + width, top, width - 44, SOFT);
        int materialTop = top + SECTION_HEIGHT;
        var materials = container.view.materials;
        if (materials.isEmpty()) text(fontRenderer, noMaterials, LIST_X, materialTop + 2, width, SOFT);
        else {
            int firstRow = Math.max(0, Math.floorDiv(LIST_Y - materialTop, MATERIAL_HEIGHT));
            int lastRow = Math.min(materialRows(), Math.floorDiv(LIST_Y + LIST_HEIGHT - materialTop - 1, MATERIAL_HEIGHT) + 1);
            for (int index = firstRow * MATERIAL_COLUMNS; index < Math.min(materials.size(), lastRow * MATERIAL_COLUMNS); index++) {
                var material = materials.get(index);
                int cellX = LIST_X + index % MATERIAL_COLUMNS * MATERIAL_WIDTH;
                int cellY = materialTop + index / MATERIAL_COLUMNS * MATERIAL_HEIGHT;
                slot(cellX + 7, cellY);
                AEKeyRendering.drawInGui(mc, cellX + 8, cellY + 1, material.key);
                GuiLabel.drawCentered(fontRenderer, material.slotAmounts(), cellX, cellY + 20, MATERIAL_WIDTH, 8,
                    material.available < material.remaining ? BAD : TEXT, false);
            }
        }
        int networkTop = materialTop + materialHeight() + SECTION_GAP;
        text(fontRenderer, networkLabel, LIST_X, networkTop, 70, TEXT);
        right(fontRenderer, networkHint, LIST_X + width, networkTop, width - 74, SOFT);
        int resourceTop = networkTop + SECTION_HEIGHT;
        var resources = container.view.resources;
        if (resources.isEmpty()) {
            text(fontRenderer, noNetworkResources, LIST_X, resourceTop + 2, width, SOFT);
            return;
        }
        int first = Math.clamp(Math.floorDiv(LIST_Y - resourceTop, RESOURCE_HEIGHT), 0, resources.size());
        int last = Math.clamp(Math.floorDiv(LIST_Y + LIST_HEIGHT - resourceTop - 1, RESOURCE_HEIGHT) + 1, 0, resources.size());
        for (int index = first; index < last; index++) {
            int rowY = resourceTop + index * RESOURCE_HEIGHT;
            var resource = resources.get(index);
            boolean completed = resource.accumulated >= resource.total;
            String value = completed ? completedLabel : resource.amounts();
            int valueWidth = Math.min(width / 2, fontRenderer.getStringWidth(value));
            text(fontRenderer, resource.name(), LIST_X, rowY + 2, width - valueWidth - 6, TEXT);
            right(fontRenderer, value, LIST_X + width, rowY + 2, valueWidth, completed ? GOOD : TEXT);
            progress(LIST_X, rowY + 14, width, 2, resource.accumulated / resource.total, completed ? GOOD : DARK);
            int percentWidth = fontRenderer.getStringWidth(resource.percentage());
            text(fontRenderer, resource.progress(), LIST_X, rowY + 19, width - percentWidth - 6, SOFT);
            right(fontRenderer, resource.percentage(), LIST_X + width, rowY + 19, percentWidth, SOFT);
            if (index + 1 < resources.size()) rect(LIST_X, rowY + RESOURCE_HEIGHT - 1, width, 1, LINE);
        }
    }

    private boolean inList(int mouseX, int mouseY) {
        return isPointInRegion(LIST_X, LIST_Y, listWidth(), LIST_HEIGHT, mouseX, mouseY);
    }

    private int queueIndex(int mouseX, int mouseY) {
        if (!isPointInRegion(QUEUE_X, QUEUE_Y, QUEUE_WIDTH, container.view.queue.size() * QUEUE_ROW, mouseX, mouseY))
            return -1;
        int rowY = mouseY - guiTop - QUEUE_Y;
        return rowY % QUEUE_ROW < QUEUE_CARD ? rowY / QUEUE_ROW : -1;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0 || !scrollbar.isVisible()) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth, mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (isPointInRegion(LIST_X, LIST_Y, LIST_SCROLL_X + 7 - LIST_X, LIST_HEIGHT, mouseX, mouseY))
            scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - Integer.signum(delta) * 17);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        int index = button == 0 ? queueIndex(mouseX, mouseY) : -1;
        if (index >= 0) {
            selected = container.view.queue.get(index).entryId();
            return;
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
        for (int index = 0; index < actions.length; index++) {
            var action = actions[index];
            if (isPointInRegion(action.x - guiLeft, action.y - guiTop, action.width, action.height, mouseX, mouseY)
                && (index < 2 || fontRenderer.getStringWidth(action.label) * 0.75F > action.width - 6))
                return List.of(action.label);
        }
        if (isPointInRegion(18, 11, titleWidth(), LINE_HEIGHT, mouseX, mouseY) && !fits(fontRenderer, title, titleWidth()))
            return List.of(title);
        if (isPointInRegion(teamX(), 11, 366 - teamX(), LINE_HEIGHT, mouseX, mouseY)
            && !fits(fontRenderer, container.teamName, 366 - teamX()))
            return List.of(container.teamName);
        if (container.view.definition != null) {
            String name = container.view.name();
            if (isPointInRegion(42, 35, researchNameWidth(), LINE_HEIGHT, mouseX, mouseY) && !fits(fontRenderer, name, researchNameWidth()))
                return List.of(name);
            if (isPointInRegion(42, 46, 324, LINE_HEIGHT, mouseX, mouseY) && !fits(fontRenderer, workload, 324))
                return List.of(workload);
        }
        int queue = queueIndex(mouseX, mouseY);
        if (queue >= 0) {
            String name = container.view.queueName(queue);
            return fits(fontRenderer, name, queueNameWidth(queue)) ? null : List.of(name);
        }
        if (!inList(mouseX, mouseY)) return null;
        int contentY = mouseY - guiTop - LIST_Y + scrollbar.getCurrentScroll() - SECTION_HEIGHT;
        if (contentY >= 0 && contentY < materialHeight() && !container.view.materials.isEmpty()) {
            int column = (mouseX - guiLeft - LIST_X) / MATERIAL_WIDTH;
            int index = contentY / MATERIAL_HEIGHT * MATERIAL_COLUMNS + column;
            if (column >= MATERIAL_COLUMNS || index >= container.view.materials.size()) return null;
            var material = container.view.materials.get(index);
            return List.of(material.name(), material.amounts());
        }
        int resourceY = mouseY - guiTop - LIST_Y + scrollbar.getCurrentScroll() - resourcesOffset();
        if (resourceY < 0 || resourceY >= container.view.resources.size() * RESOURCE_HEIGHT) return null;
        var resource = container.view.resources.get(resourceY / RESOURCE_HEIGHT);
        return List.of(resource.name(), resource.amounts(), resource.progress());
    }

    @SideOnly(Side.CLIENT)
    private static final class QueueButton extends AE2Button {
        private final @Nullable Icon icon;
        private String label = "";

        private QueueButton(int width, @Nullable Icon icon, ITextComponent message, Runnable onPress) {
            super(0, 0, width, ACTION_HEIGHT, message, onPress);
            this.icon = icon;
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            hovered = enabled && mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            GlStateManager.enableBlend();
            GlStateManager.enableDepth();
            AE2Button.renderButtonBackground(x, y, width, height, true, hovered);
            renderButtonText(minecraft.fontRenderer, hovered ? 0x517497 : LIGHT, 0);
            if (!enabled) drawRect(x, y, x + width, y + height, 0x80000000 | PANEL);
        }

        @Override
        protected void renderButtonText(FontRenderer font, int color, int yOffset) {
            if (icon != null) {
                icon.getBlitter().dest(x + (width - 8) / 2, y + (height - 8) / 2, 8, 8)
                    .blit();
            } else {
                GuiLabel.drawCentered(font, label, x + 3, y + (height - 6) / 2F, width - 6, 6, color, false);
            }
        }
    }
}
