package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.NumberEntryType;
import ae2.client.gui.style.GuiStyleManager;
import ae2.client.gui.widgets.NumberEntryWidget;
import com.circulation.prototype_eng.container.ContainerParallelController;
import com.circulation.prototype_eng.localization.ParallelControllerText;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

import java.awt.Rectangle;
import java.util.Objects;

/**
 * Uses AE2's existing number editor and priority-dialog artwork.
 */
public final class GuiParallelController extends AEBaseGui<ContainerParallelController> {
    private static final ResourceLocation BACKGROUND = new ResourceLocation("ae2", "textures/guis/priority.png");
    private final NumberEntryWidget number;

    public GuiParallelController(ContainerParallelController container, InventoryPlayer player) {
        super(container, player);
        xSize = 176;
        ySize = 125;
        number = new NumberEntryWidget(GuiStyleManager.loadStyleDoc("/screens/priority.json"), NumberEntryType.UNITLESS);
        number.setPosition(new Point(20, 30));
        number.setSize(138, 62);
        number.setTextFieldBounds(new Rectangle(60, 55, 61, 12));
        number.setMinValue(0);
        number.setMaxValue(container.maximum);
        number.setLongValue(container.selected);
        number.setOnChange(() -> number.getIntValue().ifPresent(container::select));
        number.setOnConfirm(() -> number.setFocused(false));
        widgets.add("parallelism", number);
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, 256, 256);
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        GuiLabel.draw(fontRenderer, Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 8, 6, 160, 9, 0x404040, false);
        GuiLabel.draw(fontRenderer, ParallelControllerText.Maximum.getLocal(container.maximum), 8, 98, 160, 9, 0x404040, false);
        GuiLabel.draw(fontRenderer, ParallelControllerText.Selected.getLocal(container.selected), 8, 110, 160, 9, 0x404040, false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        number.setMaxValue(container.maximum);
        if (!number.isFocused()) number.setLongValue(container.selected);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
