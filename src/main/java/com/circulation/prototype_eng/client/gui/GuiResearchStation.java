package com.circulation.prototype_eng.client.gui;

import ae2.api.client.AEKeyRendering;
import ae2.api.stacks.AEKey;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.widgets.AE2Button;
import ae2.client.gui.widgets.Scrollbar;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.container.ContainerResearchStation;
import com.circulation.prototype_eng.container.ResearchStationPacket;
import com.circulation.prototype_eng.localization.ResearchStationGuiText;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

public class GuiResearchStation extends AEBaseGui<ContainerResearchStation> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_research_station");
    private static final int TEXT = 0x404040, GOOD = 0x006600;
    private static final int LIST_X = 18, LIST_Y = 89, LIST_HEIGHT = 162, QUEUE_X = 212, QUEUE_Y = 105, QUEUE_ROW = 22;
    private final AE2Button[] actions = new AE2Button[4];
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private final DecimalFormat percentFormat = new DecimalFormat("0.#'%'");
    private long selected, knownElapsed = -1;
    private ResearchStationPacket.Definition knownDefinition;
    private String percent = "0%", level = "";
    private int scaleFactor;

    public GuiResearchStation(ContainerResearchStation container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 288;
        var labels = new ResearchStationGuiText[]{ResearchStationGuiText.Up, ResearchStationGuiText.Down,
            ResearchStationGuiText.SelectCurrent, ResearchStationGuiText.Remove};
        var operations = ResearchStationView.Action.values();
        int[] positions = {212, 238, 264, 318}, widths = {24, 24, 50, 50};
        for (int index = 0; index < actions.length; index++) {
            var operation = operations[index];
            actions[index] = new AE2Button(0, 0, widths[index], 18, labels[index].text(), () -> container.manage(selected, operation));
            widgets.add("researchAction" + index, actions[index], new Point(positions[index], 255));
        }
        scrollbar.setPosition(new Point(185, LIST_Y));
        scrollbar.setHeight(LIST_HEIGHT);
        scrollbar.setCaptureMouseWheel(false);
        widgets.add("researchResources", scrollbar);
    }

    @Override
    public void initGui() {
        super.initGui();
        container.view.invalidateText();
        knownDefinition = null;
        knownElapsed = -1;
        scaleFactor = new ScaledResolution(mc).getScaleFactor();
    }

    private int contentHeight() {
        return 12 + Math.max(14, container.view.materials.size() * 16) + 8 + 12 + Math.max(14, container.view.resources.size() * 32);
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
            level = definition == null ? "" : ResearchStationGuiText.TargetLevel.getLocal(definition.level());
        }
        if (definition != null && knownElapsed != container.elapsedTicks) {
            knownElapsed = container.elapsedTicks;
            percent = percentFormat.format(100D * container.elapsedTicks / definition.duration());
        }
        int selection = -1;
        for (int index = 0; index < container.view.queue.size(); index++)
            if (container.view.queue.get(index).entryId() == selected) selection = index;
        if (selection < 0 && !container.view.queue.isEmpty()) {
            selected = container.view.queue.getFirst().entryId();
            selection = 0;
        }
        actions[0].enabled = container.canManage && selection > 0;
        actions[1].enabled = container.canManage && selection >= 0 && selection + 1 < container.view.queue.size();
        actions[2].enabled = container.canManage && selection > 0;
        actions[3].enabled = container.canManage && selection >= 0;
        scrollbar.setRange(0, Math.max(0, contentHeight() - LIST_HEIGHT), 12);
        scrollbar.setVisible(contentHeight() > LIST_HEIGHT);
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, xSize, ySize);
        panel(x + 12, y + 29, 360, 48);
        panel(x + 12, y + 82, 186, 194);
        panel(x + 206, y + 82, 166, 194);
        Gui.drawRect(x + 18, y + 255, x + 192, y + 256, 0xff696d88);
        for (int index = 0; index < container.view.queue.size(); index++) {
            int top = y + QUEUE_Y + index * QUEUE_ROW;
            if (container.view.queue.get(index).entryId() == selected)
                Gui.drawRect(x + QUEUE_X, top - 2, x + 368, top + 18, 0xffadb0c4);
            if (index > 0) Gui.drawRect(x + QUEUE_X, top - 3, x + 368, top - 2, 0xff696d88);
        }
        if (container.view.definition != null) {
            Gui.drawRect(x + 18, y + 60, x + 366, y + 71, 0xffadb0c4);
            int fill = (int) (348D * container.elapsedTicks / container.view.definition.duration());
            Gui.drawRect(x + 18, y + 60, x + 18 + fill, y + 71, 0xff413f54);
        }
        if (contentHeight() > LIST_HEIGHT)
            Gui.drawRect(x + 184, y + LIST_Y, x + 193, y + LIST_Y + LIST_HEIGHT, 0xff696d88);
    }

    private static void panel(int x, int y, int width, int height) {
        Gui.drawRect(x, y, x + width, y + height, 0xff9a9fb4);
        GuiAtlas.inset(x, y, width, height);
    }

    private void text(String text, int x, int y, int width) {
        GuiLabel.draw(fontRenderer, text, x, y, width, 10, TEXT, false);
    }

    private void right(String text, int right, int y, int width) {
        text(text, Math.max(right - width, right - fontRenderer.getStringWidth(text)), y, width);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        text(container.getGuiTitle().getFormattedText(), 18, 12, 348);
        var definition = container.view.definition;
        if (definition == null)
            text((container.visible ? ResearchStationGuiText.Empty : ResearchStationGuiText.Denied).getLocal(), 18, 41, 348);
        else {
            if (definition.icon() != null) AEKeyRendering.drawInGui(mc, 18, 37, definition.icon());
            var state = !container.visible ? ResearchStationGuiText.Denied : container.manual ? ResearchStationGuiText.Manual
                : container.workingStations > 0 ? ResearchStationGuiText.Running : ResearchStationGuiText.Waiting;
            String status = state.getLocal();
            int statusWidth = Math.min(90, fontRenderer.getStringWidth(status));
            GuiLabel.draw(fontRenderer, status, 366 - statusWidth, 41, statusWidth, 10,
                container.workingStations > 0 ? GOOD : TEXT, false);
            int nameX = definition.icon() == null ? 18 : 41;
            int levelWidth = Math.min(82, fontRenderer.getStringWidth(level));
            int nameWidth = Math.min(175, 366 - statusWidth - levelWidth - nameX - 16);
            text(container.view.name(), nameX, 41, nameWidth);
            text(level, nameX + Math.min(nameWidth, fontRenderer.getStringWidth(container.view.name())) + 8, 41, levelWidth);
            GuiLabel.drawCentered(fontRenderer, percent, 18, 60, 348, 11, 0xf2f2f2, true);
        }
        text(ResearchStationGuiText.WorkingStations.getLocal(container.workingStations), 18, 263, 174);
        text(ResearchStationGuiText.Queue.getLocal(), QUEUE_X, 90, 100);
        right(container.view.queue.size() + " / 5", 368, 90, 40);
        for (int index = 0; index < container.view.queue.size(); index++) {
            var row = container.view.queue.get(index);
            int top = QUEUE_Y + index * QUEUE_ROW;
            text(Integer.toString(index + 1), QUEUE_X + 2, top + 3, 10);
            text(container.view.queueName(index), QUEUE_X + 16, top + 3, 88);
            right("Lv." + row.level(), 340, top + 3, 28);
            if (index == 0)
                GuiLabel.draw(fontRenderer, ResearchStationGuiText.Current.getLocal(), 346, top + 3, 22, 10, GOOD, false);
        }
        if (container.view.queue.isEmpty())
            text(ResearchStationGuiText.EmptyQueue.getLocal(), QUEUE_X + 42, QUEUE_Y + 38, 100);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor((guiLeft + LIST_X) * scaleFactor, mc.displayHeight - (guiTop + LIST_Y + LIST_HEIGHT) * scaleFactor, listWidth() * scaleFactor, LIST_HEIGHT * scaleFactor);
            drawResources();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void drawResources() {
        int top = LIST_Y - scrollbar.getCurrentScroll();
        text(ResearchStationGuiText.Resources.getLocal(), LIST_X, top, 42);
        right(ResearchStationGuiText.AvailableRemaining.getLocal(), LIST_X + listWidth(), top, 124);
        top += 12;
        for (int index1 = 0; index1 < container.view.materials.size(); index1++) {
            var material = container.view.materials.get(index1);
            icon(material.key, top + 1);
            String quantity = material.amounts();
            int quantityWidth = Math.min(listWidth() / 2, fontRenderer.getStringWidth(quantity));
            text(material.name(), LIST_X + 15, top + 3, listWidth() - quantityWidth - 21);
            right(quantity, LIST_X + listWidth(), top + 3, quantityWidth);
            top += 16;
        }
        if (container.view.materials.isEmpty()) {
            text(ResearchStationGuiText.NoMaterials.getLocal(), LIST_X, top + 2, listWidth());
            top += 14;
        }
        top += 8;
        text(ResearchStationGuiText.NetworkResources.getLocal(), LIST_X, top, 60);
        right(ResearchStationGuiText.OccupiedLimit.getLocal(), LIST_X + listWidth(), top, 106);
        top += 12;
        for (int index2 = 0; index2 < container.view.resources.size(); index2++) {
            var resource = container.view.resources.get(index2);
            int valueWidth = Math.min(listWidth() / 2, fontRenderer.getStringWidth(resource.amounts()));
            text(resource.name(), LIST_X, top + 2, listWidth() - valueWidth - 6);
            right(resource.amounts(), LIST_X + listWidth(), top + 2, valueWidth);
            text(ResearchStationGuiText.Accumulated.getLocal(), LIST_X, top + 14, 34);
            right(resource.progress(), LIST_X + listWidth(), top + 14, listWidth() - 38);
            Gui.drawRect(LIST_X, top + 27, LIST_X + listWidth(), top + 29, 0xffadb0c4);
            Gui.drawRect(LIST_X, top + 27, LIST_X + (int) (listWidth() * resource.accumulated / resource.total), top + 29, 0xff413f54);
            top += 32;
        }
        if (container.view.resources.isEmpty())
            text(ResearchStationGuiText.NoNetworkResources.getLocal(), LIST_X, top + 2, listWidth());
    }

    private void icon(AEKey key, int y) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(LIST_X, y, 0);
        GlStateManager.scale(0.6875, 0.6875, 1);
        AEKeyRendering.drawInGui(mc, 0, 0, key);
        GlStateManager.popMatrix();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth, mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && isPointInRegion(LIST_X, LIST_Y, 176, LIST_HEIGHT, mouseX, mouseY))
            scrollbar.setCurrentScroll(Math.clamp(scrollbar.getCurrentScroll() - Integer.signum(delta) * 17L, 0, Math.max(0, contentHeight() - LIST_HEIGHT)));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && isPointInRegion(QUEUE_X, QUEUE_Y - 2, 156, container.view.queue.size() * QUEUE_ROW, mouseX, mouseY)) {
            int index = (mouseY - guiTop - QUEUE_Y + 2) / QUEUE_ROW;
            selected = container.view.queue.get(index).entryId();
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (isPointInRegion(QUEUE_X + 16, QUEUE_Y - 2, 88, container.view.queue.size() * QUEUE_ROW, mouseX, mouseY)) {
            int index = (mouseY - guiTop - QUEUE_Y + 2) / QUEUE_ROW;
            var name = container.view.queueName(index);
            if (fontRenderer.getStringWidth(name) > 88) drawHoveringText(List.of(name), mouseX, mouseY, fontRenderer);
        }
        if (!isPointInRegion(LIST_X, LIST_Y, listWidth(), LIST_HEIGHT, mouseX, mouseY)) return;
        int rowY = mouseY - guiTop - LIST_Y + scrollbar.getCurrentScroll() - 12;
        if (rowY >= 0 && rowY < container.view.materials.size() * 16) {
            var row = container.view.materials.get(rowY / 16);
            drawHoveringText(List.of(row.name(), row.amounts()), mouseX, mouseY, fontRenderer);
            return;
        }
        rowY -= Math.max(14, container.view.materials.size() * 16) + 20;
        if (rowY >= 0 && rowY < container.view.resources.size() * 32) {
            var row = container.view.resources.get(rowY / 32);
            drawHoveringText(List.of(row.name(), row.amounts(), row.progress()), mouseX, mouseY, fontRenderer);
        }
    }
}
