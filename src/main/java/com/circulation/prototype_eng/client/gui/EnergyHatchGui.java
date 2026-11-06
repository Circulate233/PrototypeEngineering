package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.AEBaseGui;
import ae2.client.gui.style.BackgroundGenerator;
import ae2.util.ReadableNumberConverter;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.container.EnergyHatchContainer;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class EnergyHatchGui extends AEBaseGui<EnergyHatchContainer> {
    private static final int PLAYER_TOP = 132;
    private final ResourceLocation barTexture;
    private GuiTextField inputRate;
    private GuiTextField outputRate;
    private GuiButton autoInput;
    private GuiButton autoOutput;

    public EnergyHatchGui(EnergyHatchContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 176;
        ySize = PLAYER_TOP + 82;
        var tile = (EnergyHatchBlockEntity) container.getTarget();
        String texture = container.mode() == HatchMode.MIXED ? "gui_energy_io_hatch_" : "gui_energy_hatch_";
        barTexture = new ResourceLocation(Tags.MOD_ID, "textures/gui/gui_energy_hatch/" + texture + tile.tier().level() + ".png");
    }

    @Override
    public void initGui() {
        super.initGui();
        for (Slot slot : container.inventorySlots) {
            int index = slot.getSlotIndex();
            slot.yPos = index < 9 ? PLAYER_TOP + 58 : PLAYER_TOP + ((index - 9) / 9) * 18;
        }
        inputRate = createRateField(0, 56, container.inputRate);
        outputRate = createRateField(1, 86, container.outputRate);
        inputRate.setVisible(container.mode().hasInput());
        outputRate.setVisible(container.mode().hasOutput());
        if (container.mode().hasInput()) { addButton(new GuiButton(100, guiLeft + 111, guiTop + 54, 32, 20, GuiText.Apply.getLocal())); }
        if (container.mode().hasOutput()) { addButton(new GuiButton(101, guiLeft + 111, guiTop + 84, 32, 20, GuiText.Apply.getLocal())); }
        autoInput = addButton(new GuiButton(102, guiLeft + 8, guiTop + 108, 78, 18, ""));
        autoOutput = addButton(new GuiButton(103, guiLeft + 90, guiTop + 108, 78, 18, ""));
        autoInput.visible = container.mode().hasInput();
        autoOutput.visible = container.mode().hasOutput();
    }

    private GuiTextField createRateField(int id, int top, long value) {
        var field = new GuiTextField(id, fontRenderer, guiLeft + 8, guiTop + top, 100, 16);
        field.setMaxStringLength(19);
        field.setValidator(EnergyHatchGui::isRateText);
        field.setText(Long.toString(value));
        return field;
    }

    private static boolean isRateText(String text) {
        if (text == null || text.length() > 19) { return false; }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') { return false; }
        }
        return text.length() < 19 || text.compareTo(Long.toString(Long.MAX_VALUE)) <= 0;
    }

    private static long rate(GuiTextField field) {
        return field.getText().isEmpty() ? 0 : Long.parseLong(field.getText());
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 100 -> container.setInputRate(rate(inputRate));
            case 101 -> container.setOutputRate(rate(outputRate));
            case 102 -> container.toggleInput();
            case 103 -> container.toggleOutput();
            default -> super.actionPerformed(button);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (inputRate.getVisible()) { inputRate.mouseClicked(mouseX, mouseY, button); }
        if (outputRate.getVisible()) { outputRate.mouseClicked(mouseX, mouseY, button); }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (inputRate.isFocused()) {
                container.setInputRate(rate(inputRate));
                inputRate.setFocused(false);
                return;
            }
            if (outputRate.isFocused()) {
                container.setOutputRate(rate(outputRate));
                outputRate.setFocused(false);
                return;
            }
        }
        if (inputRate.textboxKeyTyped(typedChar, keyCode) || outputRate.textboxKeyTyped(typedChar, keyCode)) { return; }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        inputRate.updateCursorCounter();
        outputRate.updateCursorCounter();
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, x, y);
        drawRect(x + 149, y + 19, x + 167, y + 87, 0xff373737);
        drawRect(x + 150, y + 20, x + 166, y + 86, 0xff8b8b8b);
        // PM EnergyBarWidget crops the original 16x66 texture from bottom to top.
        int filled = container.capacity <= 0 ? 0 : (int) (66 * Math.clamp((double) container.energy / container.capacity, 0, 1));
        if (filled > 0) {
            GlStateManager.color(1, 1, 1, 1);
            bindTexture(barTexture);
            drawTexturedModalRect(x + 150, y + 86 - filled, 0, 256 - filled, 16, filled);
        }
        for (Slot slot : container.inventorySlots) {
            int left = x + slot.xPos - 1;
            int top = y + slot.yPos - 1;
            drawRect(left, top, left + 18, top + 18, 0xff373737);
            drawRect(left + 1, top + 1, left + 18, top + 18, 0xffffffff);
            drawRect(left + 1, top + 1, left + 17, top + 17, 0xff8b8b8b);
        }
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (!inputRate.isFocused()) { inputRate.setText(Long.toString(container.inputRate)); }
        if (!outputRate.isFocused()) { outputRate.setText(Long.toString(container.outputRate)); }
        autoInput.displayString = GuiText.AutoInput.getLocal((container.autoInput ? GuiText.On : GuiText.Off).getLocal());
        autoOutput.displayString = GuiText.AutoOutput.getLocal((container.autoOutput ? GuiText.On : GuiText.Off).getLocal());
        super.drawScreen(mouseX, mouseY, partialTicks);
        inputRate.drawTextBox();
        outputRate.drawTextBox();
        if (isPointInRegion(8, 20, 135, 20, mouseX, mouseY) || isPointInRegion(149, 19, 18, 68, mouseX, mouseY)) {
            drawHoveringText(List.of(GuiText.Energy.getLocal() + ": " + container.energy + " FE",
                GuiText.Capacity.getLocal() + ": " + container.capacity + " FE"), mouseX, mouseY);
        }
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        fontRenderer.drawString(Objects.requireNonNull(container.getGuiTitle(), "Energy hatch GUI title").getUnformattedText(), 8, 6, 0x404040);
        fontRenderer.drawString(GuiText.Energy.getLocal(), 8, 20, 0x404040);
        String energy = ReadableNumberConverter.format(Math.max(0, container.energy), 5);
        String capacity = ReadableNumberConverter.format(Math.max(0, container.capacity), 5);
        fontRenderer.drawString(energy + " / " + capacity + " FE", 8, 32, 0x404040);
        if (container.mode().hasInput()) { fontRenderer.drawString(GuiText.InputRate.getLocal(), 8, 45, 0x404040); }
        if (container.mode().hasOutput()) { fontRenderer.drawString(GuiText.OutputRate.getLocal(), 8, 75, 0x404040); }
    }
}
