package com.circulation.prototype_eng.client.gui;

import ae2.api.client.AEKeyRendering;
import ae2.api.stacks.AmountFormat;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.me.items.GuiSetProcessingPatternAmount;
import ae2.client.gui.widgets.NumberEntryButtonConfig;
import ae2.client.gui.widgets.Scrollbar;
import ae2.container.slot.AppEngSlot;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.container.MeResourceHatchContainer;
import com.circulation.prototype_eng.localization.MeAssemblyText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.Objects;

/** Original PM universal-ME artwork with native generic-key slots and AE's long amount editor. */
public final class MeResourceHatchGui extends AEBaseGui<MeResourceHatchContainer> {
    private final ResourceLocation background;
    private final Scrollbar inputScroll = new Scrollbar(HatchGuiSprites.resourceScrollStyle());
    private final Scrollbar outputScroll = new Scrollbar(HatchGuiSprites.resourceScrollStyle());
    private final ResourceColorSelector colorSelector;

    public MeResourceHatchGui(MeResourceHatchContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 195;
        ySize = container.guiHeight;
        background = GuiAtlas.texture("gui_bus/" + (container.mode() == HatchMode.MIXED ? "gui_resource_io_bus" : "gui_resource_bus"));
        inputScroll.setCaptureMouseWheel(false);
        outputScroll.setCaptureMouseWheel(false);
        widgets.add("meInputScroll", inputScroll);
        widgets.add("meOutputScroll", outputScroll);
        colorSelector = new ResourceColorSelector(() -> container.color, container::setColor);
        widgets.add("resourceColor", colorSelector);
    }

    @Override
    public void initGui() {
        super.initGui();
        inputScroll.setPosition(new Point(178, 25));
        inputScroll.setHeight(container.inputHeight - 2);
        outputScroll.setPosition(new Point(178, container.outputTop + 1));
        outputScroll.setHeight(container.outputHeight - 2);
        updateSlots();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        updateSlots();
    }

    private void updateSlots() {
        inputScroll.setVisible(container.mode().hasInput());
        outputScroll.setVisible(container.mode().hasOutput());
        inputScroll.setRange(0, Math.max(0, MeResourceHatchContainer.rows(container.snapshot.inputTotal())
            - Math.max(1, container.inputHeight / 36)), 1);
        outputScroll.setRange(0, Math.max(0, MeResourceHatchContainer.rows(container.snapshot.outputTotal())
            - Math.max(1, container.outputHeight / 18)), 1);
        container.requestView(inputScroll.getCurrentScroll(), outputScroll.getCurrentScroll());
        for (var slot : container.inventorySlots) {
            if (!container.isResourceSlot(slot.slotNumber) || !(slot instanceof AppEngSlot resource)) continue;
            boolean input = container.isInputSlot(slot.slotNumber);
            int cell = container.cell(slot.slotNumber);
            int top = input ? 24 : container.outputTop;
            int height = input ? container.inputHeight : container.outputHeight;
            int y = top + cell / MeResourceHatchContainer.COLUMNS * (input ? 36 : 18)
                + (input && !container.isMarkSlot(slot.slotNumber) ? 18 : 0) + 1;
            boolean present = cell < (input ? container.snapshot.inputs().size() : container.snapshot.outputs().size());
            resource.setSlotEnabled(present);
            resource.setActive(present && y < top + height && y + 16 > top);
            slot.xPos = MeResourceHatchContainer.GRID_LEFT + cell % MeResourceHatchContainer.COLUMNS * 18 + 1;
            slot.yPos = y;
        }
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(background, x, y, 0, 0, xSize, ySize, 256, 256);
        for (var slot : container.inventorySlots) {
            if (!container.isResourceSlot(slot.slotNumber) || !slot.isEnabled()) continue;
            clip(container.isInputSlot(slot.slotNumber));
            try {
                GuiAtlas.draw(background, x + slot.xPos - 1, y + slot.yPos - 1,
                    container.isMarkSlot(slot.slotNumber) ? 238 : 219,
                    container.isMarkSlot(slot.slotNumber) ? 220 : 238, 18, 18, 256, 256);
            } finally {
                GL11.glPopAttrib();
            }
        }
    }

    @Override
    protected void drawSlot(@NotNull Slot slot) {
        if (!container.isResourceSlot(slot.slotNumber)) {
            try {
                super.drawSlot(slot);
            } finally {
                restoreStateForVanillaItemRender();
            }
            return;
        }
        clip(container.isInputSlot(slot.slotNumber));
        try {
            super.drawSlot(slot);
        } finally {
            GL11.glPopAttrib();
            restoreStateForVanillaItemRender();
        }
    }

    private void clip(boolean input) {
        var resolution = new ScaledResolution(mc);
        int scale = resolution.getScaleFactor();
        int top = input ? 24 : container.outputTop;
        int height = input ? container.inputHeight : container.outputHeight;
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((guiLeft + 26) * scale, (mc.displayHeight - (guiTop + top + height) * scale), 144 * scale, height * scale);
    }

    @Override
    protected boolean isHovering(Slot slot, int mouseX, int mouseY) {
        return (!container.isResourceSlot(slot.slotNumber) || withinResource(slot, mouseX, mouseY))
            && super.isHovering(slot, mouseX, mouseY);
    }

    @Override
    protected boolean isPointInRegion(int left, int top, int regionWidth, int regionHeight, int x, int y) {
        if (regionWidth == 16 && regionHeight == 16) {
            for (var slot : container.inventorySlots) {
                if (container.isResourceSlot(slot.slotNumber) && slot.xPos == left && slot.yPos == top
                    && !withinResource(slot, x, y)) return false;
            }
        }
        return super.isPointInRegion(left, top, regionWidth, regionHeight, x, y);
    }

    private boolean withinResource(Slot slot, int mouseX, int mouseY) {
        boolean input = container.isInputSlot(slot.slotNumber);
        int top = input ? 24 : container.outputTop;
        int height = input ? container.inputHeight : container.outputHeight;
        return mouseX >= guiLeft + 26 && mouseX < guiLeft + 170 && mouseY >= guiTop + top && mouseY < guiTop + top + height;
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        GuiLabel.drawCentered(fontRenderer, Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 30, 9, 136, 9, 0x404040, false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        container.completeFrame();
    }

    @Override
    protected void renderHoveredToolTip(int mouseX, int mouseY) {
        if (colorSelector.blocksTooltips(mouseX - guiLeft, mouseY - guiTop)) return;
        var slot = findSlot(mouseX, mouseY);
        if (slot == null || !container.isResourceSlot(slot.slotNumber) || !(slot instanceof AppEngSlot resource)) {
            super.renderHoveredToolTip(mouseX, mouseY);
            return;
        }
        var key = resource.getGenericDisplayKey();
        var lines = new ObjectArrayList<ITextComponent>();
        if (key != null) {
            if (AEKeyRendering.get(key.getType()) == null) lines.add(key.getDisplayName());
            else lines.addAll(AEKeyRendering.getTooltip(key));
            lines.add((container.isMarkSlot(slot.slotNumber) ? MeAssemblyText.TargetStock : MeAssemblyText.StoredStock)
                .text(key.formatAmount(resource.getGenericDisplayAmount(), AmountFormat.FULL)));
        } else if (container.isMarkSlot(slot.slotNumber)) lines.add(MeAssemblyText.EmptyMark.text());
        lines.add((container.isMarkSlot(slot.slotNumber) ? MeAssemblyText.MarkUsage : MeAssemblyText.ReadOnlyBuffer).text());
        var wrapped = HatchTooltips.wrap(fontRenderer, lines, Math.min(280, width - 24));
        if (key == null) drawTooltipWithHeader(mouseX, mouseY, wrapped);
        else drawKeyTooltipWithImages(mouseX, mouseY, key, wrapped);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (colorSelector.expanded()) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }
        var slot = findSlot(mouseX, mouseY);
        if (slot != null && container.isResourceSlot(slot.slotNumber)) {
            if (container.isMarkSlot(slot.slotNumber)) {
                if (button == 2) editAmount(slot.slotNumber);
                else if (button == 0 || button == 1) container.clickMark(slot.slotNumber, button);
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void handleMouseClick(Slot slot, int index, int button, @NotNull ClickType type) {
        if (!container.isResourceSlot(index)) super.handleMouseClick(slot, index, button, type);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        var slot = findSlot(mouseX, mouseY);
        if (slot == null || !container.isResourceSlot(slot.slotNumber)) super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    public boolean handleAeMouseWheelInput() {
        if (colorSelector.expanded()) return true;
        int delta = Mouse.getEventDWheel();
        if (delta == 0) return false;
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        var slot = findSlot(x, y);
        if (slot != null && container.isMarkSlot(slot.slotNumber) && (isShiftKeyDown() || isCtrlKeyDown())) {
            adjustAmount(slot.slotNumber, delta > 0);
            return true;
        }
        if (container.mode().hasInput() && isPointInRegion(26, 24, 161, container.inputHeight, x, y)) {
            inputScroll.onMouseWheel(new Point(x - guiLeft, y - guiTop), delta > 0 ? 1 : -1);
            return true;
        }
        if (container.mode().hasOutput() && isPointInRegion(26, container.outputTop, 161, container.outputHeight, x, y)) {
            outputScroll.onMouseWheel(new Point(x - guiLeft, y - guiTop), delta > 0 ? 1 : -1);
            return true;
        }
        return super.handleAeMouseWheelInput();
    }

    private void editAmount(int index) {
        if (!container.canEditPresented()) return;
        int logical = container.logicalInputSlot(index);
        var mark = container.snapshot.mark(container.cell(index));
        if (mark == null || logical < 0) return;
        long revision = container.snapshot.markRevision();
        switchToScreen(new GuiSetProcessingPatternAmount(this, mark, next ->
            container.setTarget(revision, logical, next == null ? 0 : next.amount()), Objects.requireNonNull(container.getGuiTitle())));
    }

    private void adjustAmount(int index, boolean increase) {
        if (!container.canEditPresented()) return;
        var mark = container.snapshot.mark(container.cell(index));
        int logical = container.logicalInputSlot(index);
        if (mark == null || logical < 0) return;
        var config = NumberEntryButtonConfig.get();
        boolean control = isCtrlKeyDown();
        long value = control ? config.ctrlMultiplyFactors()[0] : config.shiftAddSteps()[0];
        var operation = control ? config.ctrlOperationMode() : config.shiftOperationMode();
        long next;
        if (operation == NumberEntryButtonConfig.OperationMode.MULTIPLY_DIVIDE) {
            next = increase ? mark.amount() > Long.MAX_VALUE / value ? Long.MAX_VALUE : mark.amount() * value : mark.amount() / value;
        } else {
            long unit = mark.what().getType().getAmountPerUnit();
            long step = value > Long.MAX_VALUE / unit ? Long.MAX_VALUE : value * unit;
            next = increase ? mark.amount() > Long.MAX_VALUE - step ? Long.MAX_VALUE : mark.amount() + step
                : Math.max(0, mark.amount() - step);
        }
        container.setTarget(container.snapshot.markRevision(), logical, next);
    }
}
