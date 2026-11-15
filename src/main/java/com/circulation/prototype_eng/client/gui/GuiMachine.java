package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.widgets.ITooltip;
import com.circulation.prototype_eng.api.machine.ControllerInventory;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.container.ContainerMachine;
import com.circulation.prototype_eng.container.MachineGuiSnapshot;
import com.circulation.prototype_eng.localization.MachineGuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Mouse;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.List;

/**
 * Default controller status and process roster in the original PM panel geometry.
 */
public final class GuiMachine extends AEBaseGui<ContainerMachine> {
    private static final ResourceLocation MAIN = GuiAtlas.texture("gui_controller_a");
    private static final ResourceLocation PROGRESS = GuiAtlas.texture("gui_controller_states");
    private static final int LIST_X = 27, LIST_Y = 8, ROW_HEIGHT = 33, LIST_HEIGHT = 238;
    private static final int SCROLL_X = 120, SCROLL_WIDTH = 13;
    private int scrollIndex;
    private int requestedOffset = -1;
    private boolean dragging;
    private int dragOffset;

    public GuiMachine(ContainerMachine container, InventoryPlayer player) {
        super(container, player);
        xSize = 384;
        ySize = 256;
        widgets.add("mainPage", new PageTab(0, 0, 0), new Point(0, 0));
    }

    @Override
    public void initGui() {
        super.initGui();
        updateView();
    }

    private int maxScroll() {
        return Math.max(0, container.snapshot.totalSlots() - MachineGuiSnapshot.MAX_ROWS);
    }

    private void updateView() {
        scrollIndex = Math.clamp(scrollIndex, 0, maxScroll());
        if (scrollIndex != requestedOffset) {
            requestedOffset = scrollIndex;
            container.requestView(scrollIndex, MachineGuiSnapshot.MAX_ROWS);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        updateView();
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        // The source image also contains an empty extension tab; draw only the body and main tab.
        GuiAtlas.draw(MAIN, x + 19, y, 19, 0, 365, 256, 384, 256);
        GuiAtlas.draw(MAIN, x, y, 0, 0, 19, 22, 384, 256);
        drawRect(x + 140, y + 9, x + 376, y + 165, 0x4c424242);
        for (int row = 0; row < container.snapshot.entries().size(); row++) {
            int top = y + LIST_Y + row * ROW_HEIGHT;
            GuiAtlas.draw(PROGRESS, x + LIST_X, top, 0, 0, 89, ROW_HEIGHT, 256, 256);
            int filled = container.snapshot.entries().get(row).percent() * 89 / 100;
            if (filled > 0) GuiAtlas.draw(PROGRESS, x + LIST_X, top, 0, 34, filled, ROW_HEIGHT, 256, 256);
        }
    }

    @Override
    @SuppressWarnings("DataFlowIssue") // The container constructor sets its AE title before this GUI opens.
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        GuiLabel.draw(fontRenderer, container.getGuiTitle().getFormattedText(), 144, 13, 228, 10, 0xffffff, true);
        var snapshot = container.snapshot;
        var details = snapshot.details();
        GuiLabel.draw(fontRenderer, MachineGuiText.Structure.getLocal((snapshot.formed() ? MachineGuiText.Formed : MachineGuiText.NotFormed).getLocal()),
            144, 27, 228, 10, snapshot.formed() ? 0x55ff55 : 0xff5555, false);
        int lineY = 40;
        if (snapshot.formed()) {
            GuiLabel.draw(fontRenderer, MachineGuiText.Status.getLocal((snapshot.active()
                ? MachineGuiText.status(snapshot.overallStatus()) : MachineGuiText.Inactive).getLocal()), 144, lineY, 228, 10, 0xe0e0e0, false);
            lineY += 13;
        }
        if (snapshot.active()) {
            GuiLabel.draw(fontRenderer, details.crossRecipe() ? MachineGuiText.Recipes.getLocal(details.running())
                : MachineGuiText.Threads.getLocal(details.running(), details.threadLimit()), 144, lineY, 228, 10, 0xe0e0e0, false);
            GuiLabel.draw(fontRenderer, MachineGuiText.Parallelism.getLocal(details.usedParallelism(), details.parallelismLimit()),
                144, lineY + 13, 228, 10, 0xe0e0e0, false);
        }
        for (int row = 0; row < snapshot.entries().size(); row++) {
            var entry = snapshot.entries().get(row);
            int top = LIST_Y + row * ROW_HEIGHT;
            boolean idle = entry.recipe().isEmpty();
            boolean error = entry.status() == WorkSlotStatus.FAILED || entry.status() == WorkSlotStatus.BLOCKED;
            String label = details.crossRecipe() ? entry.recipe().substring(entry.recipe().indexOf(':') + 1) : MachineGuiText.ThreadRow.getLocal(entry.name());
            fontRenderer.drawString(fontRenderer.trimStringToWidth(label, idle ? 81 : 51), LIST_X + 4, top + 4, 0xffffff);
            if (!idle) {
                String percent = entry.percent() + "%";
                fontRenderer.drawString(percent, LIST_X + 85 - fontRenderer.getStringWidth(percent), top + 4, error ? 0xff5555 : 0x55ff55);
            }
            String status = MachineGuiText.reason(entry);
            fontRenderer.drawString(fontRenderer.trimStringToWidth(status, 81), LIST_X + 4, top + 16,
                error ? 0xff5555 : idle ? 0xaaaaaa : 0xffffff);
        }
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        for (int slot = 0; slot < ControllerInventory.CAPACITY; slot++)
            if (!container.isControllerSlotAvailable(slot)) {
                int left = ContainerMachine.inventoryX(slot);
                int top = ContainerMachine.inventoryY(slot);
                drawRect(left, top, left + 16, top + 16, 0xa0ffffff);
            }
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        if (maxScroll() > 0) {
            int handleY = handleY();
            boolean hover = isPointInRegion(SCROLL_X, handleY, SCROLL_WIDTH, 15, mouseX, mouseY);
            mc.getTextureManager().bindTexture(GuiAtlas.STATES);
            GlStateManager.color(1, 1, 1, 1);
            Gui.drawScaledCustomSizeModalRect(SCROLL_X, handleY, dragging ? 172 : hover ? 159 : 146, 1,
                13, 15, SCROLL_WIDTH, 15, 256, 256);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (isPointInRegion(LIST_X, LIST_Y, 89, LIST_HEIGHT, mouseX, mouseY)) {
            int row = (mouseY - guiTop - LIST_Y) / ROW_HEIGHT;
            if (row < container.snapshot.entries().size()) {
                var entry = container.snapshot.entries().get(row);
                var lines = new ObjectArrayList<String>(3);
                String reason = MachineGuiText.reason(entry);
                if (fontRenderer.getStringWidth(reason) > 81) lines.add(reason);
                if (!entry.recipe().isEmpty()) lines.add(entry.recipe());
                if (entry.parallelism() > 0) lines.add(MachineGuiText.RecipeParallelism.getLocal(entry.parallelism()));
                if (!lines.isEmpty()) drawHoveringText(lines, mouseX, mouseY);
            }
        }
    }

    private int handleY() {
        return LIST_Y + (int) Math.round(223.0 * scrollIndex / Math.max(1, maxScroll()));
    }

    @Override
    protected void actionPerformed(@NotNull GuiButton button) throws IOException {
        if (button instanceof PageTab) {
            dragging = false;
        } else super.actionPerformed(button);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && maxScroll() > 0 && isPointInRegion(SCROLL_X, handleY(), SCROLL_WIDTH, 15, mouseX, mouseY)) {
            dragging = true;
            dragOffset = mouseY - guiTop - handleY();
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1));
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (dragging && button == 0) {
            scrollIndex = (int) Math.round(Math.clamp(mouseY - guiTop - LIST_Y - dragOffset, 0, 223) / 223.0 * maxScroll());
            updateView();
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        if (button == 0) dragging = false;
        super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (wheel != 0 && isPointInRegion(LIST_X, LIST_Y, SCROLL_X + SCROLL_WIDTH - LIST_X, LIST_HEIGHT, mouseX, mouseY)) {
            scrollIndex -= Integer.signum(wheel);
            updateView();
        }
    }

    private static final class PageTab extends GuiButton implements ITooltip {
        private PageTab(int id, int x, int y) {
            super(id, x, y, 21, 22, "");
        }

        @Override
        public @NotNull List<ITextComponent> getTooltipMessage() {
            return List.of(new TextComponentString(MachineGuiText.MainPage.getLocal()));
        }

        @Override
        public Rectangle getTooltipArea() {
            return new Rectangle(x, y, width, height);
        }

        @Override
        public boolean isTooltipAreaVisible() {
            return visible;
        }

        @Override
        public void drawButton(@NotNull Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            GuiAtlas.state(x, y, 233, 182, 21, 22);
        }
    }
}
