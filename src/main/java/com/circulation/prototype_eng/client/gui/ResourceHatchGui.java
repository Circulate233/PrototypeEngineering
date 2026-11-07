package com.circulation.prototype_eng.client.gui;

import ae2.api.behaviors.ContainerItemStrategies;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.style.BackgroundGenerator;
import ae2.client.gui.widgets.Scrollbar;
import ae2.container.slot.AppEngSlot;
import ae2.core.network.InitNetwork;
import ae2.core.network.serverbound.InventoryActionPacket;
import ae2.helpers.InventoryAction;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import java.io.IOException;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

public final class ResourceHatchGui extends AEBaseGui<ResourceHatchContainer> {
    private final Scrollbar inputScrollbar;
    private final Scrollbar outputScrollbar;
    private int visibleRows;
    private int playerTop;
    private int controlsTop;
    private GuiTextField inputRate;
    private GuiTextField outputRate;

    public ResourceHatchGui(ResourceHatchContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = container.guiWidth;
        ySize = container.playerTop + 82;
        inputScrollbar = createScrollbar(true);
        outputScrollbar = createScrollbar(false);
        widgets.add("inputScrollbar", inputScrollbar);
        widgets.add("outputScrollbar", outputScrollbar);
    }

    private Scrollbar createScrollbar(boolean input) {
        return new Scrollbar(Scrollbar.SMALL) {
            @Override
            public boolean onMouseWheel(Point mouse, double delta) {
                int left = input ? 8 : container.outputLeft;
                if (mouse.x() < left || mouse.x() >= left + container.columns * 18 + 9
                    || mouse.y() < 31 || mouse.y() >= 31 + visibleRows * 18) {
                    return false;
                }
                boolean handled = super.onMouseWheel(mouse, delta);
                updateResourceSlots();
                return handled;
            }
        };
    }

    @Override
    public void initGui() {
        visibleRows = Math.min(container.rows, Math.clamp((height - 140 - container.controlsHeight()) / 18, 1, 5));
        playerTop = 50 + visibleRows * 18 + container.controlsHeight();
        controlsTop = 36 + visibleRows * 18;
        ySize = playerTop + 82;
        configureScrollbar(inputScrollbar, 8, container.mode().hasInput());
        configureScrollbar(outputScrollbar, container.outputLeft, container.mode().hasOutput());
        super.initGui();
        if (container.hasFluidSettings()) {
            inputRate = createRateField(0, controlsTop + 11, container.fluidInputRate);
            outputRate = createRateField(1, controlsTop + 41, container.fluidOutputRate);
            inputRate.setVisible(container.mode().hasInput());
            outputRate.setVisible(container.mode().hasOutput());
            if (container.mode().hasInput()) {
                addButton(new GuiButton(100, guiLeft + 111, guiTop + controlsTop + 10, 32, 16, GuiText.Apply.getLocal()));
            }
            if (container.mode().hasOutput()) {
                addButton(new GuiButton(101, guiLeft + 111, guiTop + controlsTop + 40, 32, 16, GuiText.Apply.getLocal()));
            }
        }
        addButton(new GuiButton(102, guiLeft + xSize - 96, guiTop + controlsTop + (container.hasFluidSettings() ? 60 : 0),
            88, 18, GuiText.ClearFluids.getLocal()));
        for (Slot slot : container.inventorySlots) {
            if (!container.isResourceSlot(slot.slotNumber)) {
                int index = slot.getSlotIndex();
                slot.yPos = index < 9 ? playerTop + 58 : playerTop + ((index - 9) / 9) * 18;
            }
        }
        updateResourceSlots();
    }

    private GuiTextField createRateField(int id, int top, long value) {
        var field = new GuiTextField(id, fontRenderer, guiLeft + 8, guiTop + top, 100, 14);
        field.setMaxStringLength(19);
        field.setValidator(text -> text != null && text.matches("[0-9]{0,19}")
            && (text.length() < 19 || text.compareTo(Long.toString(Long.MAX_VALUE)) <= 0));
        field.setText(Long.toString(value));
        return field;
    }

    private static long rate(GuiTextField field) {
        return field.getText().isEmpty() ? 0 : Long.parseLong(field.getText());
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 100 -> container.setFluidInputRate(rate(inputRate));
            case 101 -> container.setFluidOutputRate(rate(outputRate));
            case 102 -> container.clearFluids();
            default -> super.actionPerformed(button);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (inputRate != null && inputRate.getVisible()) {
            inputRate.mouseClicked(mouseX, mouseY, button);
        }
        if (outputRate != null && outputRate.getVisible()) {
            outputRate.mouseClicked(mouseX, mouseY, button);
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (inputRate != null && inputRate.isFocused()) {
                container.setFluidInputRate(rate(inputRate));
                inputRate.setFocused(false);
                return;
            }
            if (outputRate != null && outputRate.isFocused()) {
                container.setFluidOutputRate(rate(outputRate));
                outputRate.setFocused(false);
                return;
            }
        }
        if (inputRate != null && inputRate.textboxKeyTyped(typedChar, keyCode)
            || outputRate != null && outputRate.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (inputRate != null) {
            inputRate.updateCursorCounter();
        }
        if (outputRate != null) {
            outputRate.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (inputRate != null && !inputRate.isFocused()) {
            inputRate.setText(Long.toString(container.fluidInputRate));
        }
        if (outputRate != null && !outputRate.isFocused()) {
            outputRate.setText(Long.toString(container.fluidOutputRate));
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (inputRate != null) {
            inputRate.drawTextBox();
        }
        if (outputRate != null) {
            outputRate.drawTextBox();
        }
    }

    private void configureScrollbar(Scrollbar scrollbar, int left, boolean visible) {
        scrollbar.setPosition(new Point(left + container.columns * 18 + 2, 32));
        scrollbar.setHeight(visibleRows * 18 - 1);
        scrollbar.setRange(0, container.rows - visibleRows, 1);
        scrollbar.setVisible(visible);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        updateResourceSlots();
    }

    private void updateResourceSlots() {
        for (Slot slot : container.inventorySlots) {
            if (container.isResourceSlot(slot.slotNumber) && slot instanceof AppEngSlot resource) {
                boolean input = container.isInputSlot(slot.slotNumber);
                Scrollbar scrollbar = input ? inputScrollbar : outputScrollbar;
                int row = slot.getSlotIndex() / container.columns - scrollbar.getCurrentScroll();
                boolean visible = row >= 0 && row < visibleRows;
                resource.setActive(visible);
                slot.xPos = visible ? (input ? 8 : container.outputLeft) + (slot.getSlotIndex() % container.columns) * 18 : -9999;
                slot.yPos = visible ? 32 + row * 18 : -9999;
            }
        }
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, x, y);
        for (Slot slot : container.inventorySlots) {
            if (!slot.isEnabled()) {
                continue;
            }
            int left = x + slot.xPos - 1;
            int top = y + slot.yPos - 1;
            drawRect(left, top, left + 18, top + 18, 0xff373737);
            drawRect(left + 1, top + 1, left + 18, top + 18, 0xffffffff);
            drawRect(left + 1, top + 1, left + 17, top + 17, 0xff8b8b8b);
        }
        drawScrollTrack(inputScrollbar, x, y);
        drawScrollTrack(outputScrollbar, x, y);
        GlStateManager.color(1, 1, 1, 1);
    }

    private static void drawScrollTrack(Scrollbar scrollbar, int x, int y) {
        if (scrollbar.isVisible()) {
            var bounds = scrollbar.getBounds();
            drawRect(x + bounds.x - 1, y + bounds.y - 1, x + bounds.x + bounds.width + 1, y + bounds.y + bounds.height + 1, 0xff373737);
            drawRect(x + bounds.x, y + bounds.y, x + bounds.x + bounds.width, y + bounds.y + bounds.height, 0xff8b8b8b);
        }
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        fontRenderer.drawString(Objects.requireNonNull(container.getGuiTitle(), "Resource hatch GUI title").getUnformattedText(), 8, 6, 0x404040);
        if (container.mode().hasInput()) {
            fontRenderer.drawString(GuiText.Input.getLocal(), 8, 20, 0x234c33);
        }
        if (container.mode().hasOutput()) {
            fontRenderer.drawString(GuiText.Output.getLocal(), container.outputLeft, 20, 0x674226);
        }
        if (container.hasFluidSettings() && container.mode().hasInput()) {
            fontRenderer.drawString(GuiText.FluidInputRate.getLocal(), 8, controlsTop, 0x404040);
        }
        if (container.hasFluidSettings() && container.mode().hasOutput()) {
            fontRenderer.drawString(GuiText.FluidOutputRate.getLocal(), 8, controlsTop + 30, 0x404040);
        }
        fontRenderer.drawString(playerInventory.getDisplayName().getUnformattedText(), (xSize - 162) / 2, playerTop - 11, 0x404040);
    }

    @Override
    protected void handleMouseClick(Slot slot, int slotId, int button, @NotNull ClickType click) {
        if (container.isResourceSlot(slotId) && slot instanceof AppEngSlot resource
            && (click == ClickType.PICKUP || click == ClickType.QUICK_MOVE) && !getCarriedItem().isEmpty()) {
            if (button == 1 && container.isInputSlot(slotId)
                && ContainerItemStrategies.getEmptyingAction(getCarriedItem()) != null) {
                send(isShiftKeyDown() ? InventoryAction.EMPTY_ENTIRE_ITEM : InventoryAction.EMPTY_ITEM, slotId);
                return;
            }
            var key = resource.getGenericDisplayKey();
            if (button == 0 && key != null && !(key instanceof AEItemKey)) {
                var context = ContainerItemStrategies.findCarriedContextForKey(key, playerInventory.player, container);
                if (context != null && context.insert(key, resource.getGenericDisplayAmount(), Actionable.SIMULATE) > 0) {
                    send(isShiftKeyDown() ? InventoryAction.FILL_ENTIRE_ITEM : InventoryAction.FILL_ITEM, slotId);
                    return;
                }
            }
        }
        super.handleMouseClick(slot, slotId, button, click);
    }

    private void send(InventoryAction action, int slot) {
        InitNetwork.sendToServer(new InventoryActionPacket(container.windowId, action, slot, 0));
    }
}
