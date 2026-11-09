package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.container.EnergyHatchContainer;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
/** Original PM EnergyHatchGUI: 192x168, tier background, borderless rate fields and side icons. */
public final class EnergyHatchGui extends AEBaseGui<EnergyHatchContainer> {
    private final ResourceLocation background;
    private HatchRateField inputRate;
    private HatchRateField outputRate;
    private HatchToggleButton autoInput;
    private HatchToggleButton autoOutput;

    public EnergyHatchGui(EnergyHatchContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 192;
        ySize = 168;
        String file = container.mode() == HatchMode.MIXED ? "gui_energy_io_hatch_" : "gui_energy_hatch_";
        background = GuiAtlas.texture("gui_energy_hatch/" + file + container.tier().level());
    }

    @Override public void initGui() {
        super.initGui();
        inputRate = createRateField(0, 52, container.inputRate);
        outputRate = createRateField(1, 68, container.outputRate);
        autoInput = addButton(new HatchToggleButton(100, guiLeft + 4, guiTop + 13, true,
            () -> container.autoInput, container.mode().hasInput()));
        autoOutput = addButton(new HatchToggleButton(101, guiLeft + 4, guiTop + 31, false,
            () -> container.autoOutput, container.mode().hasOutput()));
    }

    private HatchRateField createRateField(int id, int top, long value) {
        var field = new HatchRateField(id, fontRenderer, guiLeft + 40, guiTop + top);
        field.setText(Long.toString(value));
        return field;
    }

    private static long rate(GuiTextField field) { return HatchExpression.parse(field.getText()); }

    @Override protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (button == 0 && (inputRate.isFocused() || outputRate.isFocused())) {
            inputRate.dragTo(mouseX);
            outputRate.dragTo(mouseX);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 100) container.toggleInput();
        else if (button.id == 101) container.toggleOutput();
        else super.actionPerformed(button);
    }

    private void commitFocused() {
        if (inputRate != null && inputRate.isFocused()) {
            container.setInputRate(rate(inputRate));
            inputRate.setFocused(false);
        }
        if (outputRate != null && outputRate.isFocused()) {
            container.setOutputRate(rate(outputRate));
            outputRate.setFocused(false);
        }
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        boolean inputFocused = inputRate.isFocused(), outputFocused = outputRate.isFocused();
        inputRate.mouseClicked(mouseX, mouseY, button);
        outputRate.mouseClicked(mouseX, mouseY, button);
        if (inputFocused && !inputRate.isFocused()) container.setInputRate(rate(inputRate));
        if (outputFocused && !outputRate.isFocused()) container.setOutputRate(rate(outputRate));
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitFocused();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && (inputRate.isFocused() || outputRate.isFocused())) { commitFocused(); return; }
        if (inputRate.textboxKeyTyped(typedChar, keyCode) || outputRate.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        inputRate.updateCursorCounter();
        outputRate.updateCursorCounter();
    }

    @Override public void onGuiClosed() { commitFocused(); super.onGuiClosed(); }

    @Override public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(background, x, y, 0, 0, 192, 168, 256, 256);
        int filled = container.capacity <= 0 ? 0 : (int) (66 * Math.clamp((double) container.energy / container.capacity, 0, 1));
        if (filled > 0) GuiAtlas.draw(background, x + 146, y + 10 + 66 - filled, 0, 256 - filled, 16, filled, 256, 256);
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (!inputRate.isFocused()) inputRate.setText(Long.toString(container.inputRate));
        if (!outputRate.isFocused()) outputRate.setText(Long.toString(container.outputRate));
        super.drawScreen(mouseX, mouseY, partialTicks);
        inputRate.drawTextBox();
        outputRate.drawTextBox();
        if (autoInput.isMouseOver()) drawHoveringText(List.of(GuiText.AutoInput.getLocal()), mouseX, mouseY);
        else if (autoOutput.isMouseOver()) drawHoveringText(List.of(GuiText.AutoOutput.getLocal()), mouseX, mouseY);
        else if (isPointInRegion(146, 10, 16, 66, mouseX, mouseY)) {
            long capacity = Math.max(0, container.capacity);
            drawHoveringText(List.of(GuiNumbers.grouped(Math.clamp(container.energy, 0, capacity)) + " / "
                + GuiNumbers.grouped(capacity) + " FE"), mouseX, mouseY);
        } else if (fontRenderer.getStringWidth(inputRate.getText()) > 89 && isPointInRegion(40, 52, 97, 10, mouseX, mouseY)) drawHoveringText(List.of(inputRate.getText()), mouseX, mouseY);
        else if (fontRenderer.getStringWidth(outputRate.getText()) > 89 && isPointInRegion(40, 68, 97, 10, mouseX, mouseY)) drawHoveringText(List.of(outputRate.getText()), mouseX, mouseY);
    }

    @Override public void drawFG(int x, int y, int mouseX, int mouseY) {
        fontRenderer.drawString(Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 65, 7, 0x404040);
        long capacity = Math.max(0, container.capacity), energy = Math.clamp(container.energy, 0, capacity);
        fontRenderer.drawString(GuiNumbers.compact(energy) + " / " + GuiNumbers.compact(capacity) + " FE", 44, 25, 0x404040);
    }
}
