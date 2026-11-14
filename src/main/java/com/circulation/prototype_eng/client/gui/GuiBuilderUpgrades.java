package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.style.BackgroundGenerator;
import ae2.client.gui.style.GuiStyleManager;
import ae2.client.gui.style.PaletteColor;
import ae2.client.gui.widgets.TabButton;
import ae2.client.gui.widgets.UpgradesPanel;
import ae2.container.SlotSemantics;
import ae2.core.localization.GuiText;
import com.circulation.prototype_eng.container.ContainerBuilderUpgrades;
import com.circulation.prototype_eng.localization.StructureToolText;
import net.minecraft.entity.player.InventoryPlayer;

public final class GuiBuilderUpgrades extends AEBaseGui<ContainerBuilderUpgrades> {
    private final int textColor;

    public GuiBuilderUpgrades(ContainerBuilderUpgrades container, InventoryPlayer player) {
        super(container, player);
        xSize = 176;
        ySize = 118;
        textColor = GuiStyleManager.loadStyleDoc("/screens/common/common.json").getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        var panel = UpgradesPanel.create(widgets, container.getSlots(SlotSemantics.UPGRADE),
            container.getSlots(SlotSemantics.WIRELESS_SINGULARITY), container.host());
        panel.setPosition(new Point(xSize - 1, 0));
        widgets.add("upgrades", panel);
        widgets.add("back", new TabButton(container.host().getItemStack(), StructureToolText.BuilderTitle.text(), container::back),
            new Point(152, -5));
    }

    @Override
    public void drawBG(int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, offsetX, offsetY);
        for (var slot : container.getSlots(SlotSemantics.PLAYER_INVENTORY))
            Icon.SLOT_BACKGROUND.getBlitter().dest(offsetX + slot.xPos - 1, offsetY + slot.yPos - 1).blit();
        for (var slot : container.getSlots(SlotSemantics.PLAYER_HOTBAR))
            Icon.SLOT_BACKGROUND.getBlitter().dest(offsetX + slot.xPos - 1, offsetY + slot.yPos - 1).blit();
    }

    @Override
    public void drawFG(int offsetX, int offsetY, int mouseX, int mouseY) {
        fontRenderer.drawString(StructureToolText.BuilderUpgrades.getLocal(), 8, 6, textColor);
        fontRenderer.drawString(GuiText.Inventory.getLocal(), 8, 23, textColor);
    }
}
