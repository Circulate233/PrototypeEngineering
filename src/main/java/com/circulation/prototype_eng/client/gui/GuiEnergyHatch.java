package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.container.ContainerEnergyHatch;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * PM tier art with independent IO meters, borderless rate fields and no upgrade slots.
 */
public final class GuiEnergyHatch extends AEBaseGui<ContainerEnergyHatch> {
    private final ResourceLocation background;
    private final boolean mixed;
    private final int fieldWidth;
    private HatchRateField inputRate;
    private HatchRateField outputRate;

    public GuiEnergyHatch(ContainerEnergyHatch container, InventoryPlayer player) {
        super(container, player);
        xSize = 195;
        ySize = 168;
        mixed = container.mode() == HatchMode.MIXED;
        fieldWidth = mixed ? 97 : 120;
        String file = mixed ? "gui_energy_io_hatch_" : "gui_energy_hatch_";
        background = GuiAtlas.texture("gui_energy_hatch/" + file + container.tier().level());
        widgets.add("autoInput", new HatchToggleButton(true,
            () -> container.autoInput, container.mode().hasInput(), container::toggleInput, new Point(4, 13)));
        widgets.add("autoOutput", new HatchToggleButton(false,
            () -> container.autoOutput, container.mode().hasOutput(), container::toggleOutput, new Point(4, 31)));
    }

    private static long rate(GuiTextField field) {
        return HatchExpression.parse(field.getText());
    }

    @Override
    public void initGui() {
        super.initGui();
        inputRate = createRateField(0, 52, container.inputRate);
        outputRate = createRateField(1, 68, container.outputRate);
    }

    private HatchRateField createRateField(int id, int top, long value) {
        var field = new HatchRateField(id, fontRenderer, guiLeft + 40, guiTop + top, fieldWidth);
        field.setText(Long.toString(value));
        return field;
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

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        boolean inputFocused = inputRate.isFocused(), outputFocused = outputRate.isFocused();
        inputRate.mouseClicked(mouseX, mouseY, button);
        outputRate.mouseClicked(mouseX, mouseY, button);
        if (inputFocused && !inputRate.isFocused()) container.setInputRate(rate(inputRate));
        if (outputFocused && !outputRate.isFocused()) container.setOutputRate(rate(outputRate));
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitFocused();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && (inputRate.isFocused() || outputRate.isFocused())) {
            commitFocused();
            return;
        }
        if (inputRate.textboxKeyTyped(typedChar, keyCode) || outputRate.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (button == 0 && (inputRate.isFocused() || outputRate.isFocused())) {
            inputRate.dragTo(mouseX);
            outputRate.dragTo(mouseX);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        inputRate.updateCursorCounter();
        outputRate.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        commitFocused();
        super.onGuiClosed();
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(background, x, y, 0, 0, xSize, ySize, 256, 256);
        drawEnergyBar(x, y, mixed ? 146 : 169, container.energy, container.capacity);
        if (mixed) drawEnergyBar(x, y, 169, container.outputEnergy, container.outputCapacity);
    }

    private void drawEnergyBar(int x, int y, int left, long energy, long capacity) {
        int filled = capacity <= 0 ? 0 : (int) (66 * Math.clamp((double) energy / capacity, 0, 1));
        if (filled > 0) GuiAtlas.draw(background, x + left, y + 76 - filled, 0, 256 - filled, 16, filled, 256, 256);
    }

    private void drawEnergyAmount(int top, long energy, long capacity) {
        capacity = Math.max(0, capacity);
        GuiLabel.draw(fontRenderer, GuiNumbers.compact(Math.clamp(energy, 0, capacity)) + " / " + GuiNumbers.compact(capacity) + " FE",
            44, top, fieldWidth - 8, 10, 0x404040, false);
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        GuiLabel.drawCentered(fontRenderer, Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 29, 8, fieldWidth + 9, 10, 0x404040, false);
        drawEnergyAmount(25, container.energy, container.capacity);
        if (mixed) drawEnergyAmount(38, container.outputEnergy, container.outputCapacity);
        // Fields store screen coordinates; draw them before AE draws button/slot tooltips.
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(-x, -y, 0);
            inputRate.drawTextBox();
            outputRate.drawTextBox();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (!inputRate.isFocused()) inputRate.setText(Long.toString(container.inputRate));
        if (!outputRate.isFocused()) outputRate.setText(Long.toString(container.outputRate));
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hovered(26, 7, fieldWidth + 15, 12, mouseX, mouseY))
            drawHoveringText(List.of(Objects.requireNonNull(container.getGuiTitle()).getFormattedText()), mouseX, mouseY);
        else if (mixed && (hovered(166, 7, 22, 72, mouseX, mouseY) || hovered(26, 37, fieldWidth + 15, 12, mouseX, mouseY)))
            drawEnergyTooltip(mouseX, mouseY, GuiText.Output, container.outputEnergy, container.outputCapacity);
        else if (hovered(mixed ? 143 : 166, 7, 22, 72, mouseX, mouseY) || hovered(26, 24, fieldWidth + 15, 12, mouseX, mouseY))
            drawEnergyTooltip(mouseX, mouseY, mixed ? GuiText.Input : GuiText.Energy, container.energy, container.capacity);
        else if (hovered(26, 51, fieldWidth + 15, 12, mouseX, mouseY))
            drawHoveringText(List.of(GuiText.InputRate.getLocal(), inputRate.getText()), mouseX, mouseY);
        else if (hovered(26, 67, fieldWidth + 15, 12, mouseX, mouseY))
            drawHoveringText(List.of(GuiText.OutputRate.getLocal(), outputRate.getText()), mouseX, mouseY);
    }

    private boolean hovered(int left, int top, int width, int height, int mouseX, int mouseY) {
        int x = mouseX - guiLeft, y = mouseY - guiTop;
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private void drawEnergyTooltip(int mouseX, int mouseY, GuiText label, long energy, long capacity) {
        capacity = Math.max(0, capacity);
        drawHoveringText(List.of(label.getLocal(), GuiNumbers.grouped(Math.clamp(energy, 0, capacity)) + " / "
            + GuiNumbers.grouped(capacity) + " FE"), mouseX, mouseY);
    }
}
