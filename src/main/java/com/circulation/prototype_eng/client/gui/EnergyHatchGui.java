package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.AEBaseGui;
import ae2.client.gui.style.BackgroundGenerator;
import com.circulation.prototype_eng.container.EnergyHatchContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;

public final class EnergyHatchGui extends AEBaseGui<EnergyHatchContainer> {
    public EnergyHatchGui(EnergyHatchContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 176;
        ySize = 170;
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, x, y);
        drawRect(x + 8, y + 61, x + 168, y + 70, 0xff373737);
        int filled = container.capacity <= 0 ? 0 : (int) (158 * ((double) container.energy / container.capacity));
        drawRect(x + 9, y + 62, x + 9 + filled, y + 69, 0xffc97e24);
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
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        fontRenderer.drawString(container.getGuiTitle().getUnformattedText(), 8, 6, 0x404040);
        fontRenderer.drawString(I18n.format("prototype_eng.gui.energy"), 8, 20, 0x404040);
        fontRenderer.drawString(container.energy + " FE", 8, 30, 0x404040);
        fontRenderer.drawString(I18n.format("prototype_eng.gui.capacity"), 8, 40, 0x404040);
        fontRenderer.drawString(container.capacity + " FE", 8, 50, 0x404040);
        fontRenderer.drawString(playerInventory.getDisplayName().getUnformattedText(), 8, 77, 0x404040);
    }
}
