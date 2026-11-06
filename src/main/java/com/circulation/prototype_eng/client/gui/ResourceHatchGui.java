package com.circulation.prototype_eng.client.gui;

import ae2.api.behaviors.ContainerItemStrategies;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.style.BackgroundGenerator;
import ae2.container.slot.AppEngSlot;
import ae2.core.network.InitNetwork;
import ae2.core.network.serverbound.InventoryActionPacket;
import ae2.helpers.InventoryAction;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;

public final class ResourceHatchGui extends AEBaseGui<ResourceHatchContainer> {
    public ResourceHatchGui(ResourceHatchContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = container.guiWidth;
        ySize = container.playerTop + 82;
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, x, y);
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
        if (container.mode().hasInput()) {
            fontRenderer.drawString(I18n.format("prototype_eng.gui.input"), 8, 20, 0x234c33);
        }
        if (container.mode().hasOutput()) {
            int left = container.mode() == HatchMode.MIXED ? xSize - 8 - container.columns * 18 : 8;
            fontRenderer.drawString(I18n.format("prototype_eng.gui.output"), left, 20, 0x674226);
        }
        fontRenderer.drawString(playerInventory.getDisplayName().getUnformattedText(), (xSize - 162) / 2, container.playerTop - 11, 0x404040);
    }

    @Override
    protected void handleMouseClick(Slot slot, int slotId, int button, ClickType click) {
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
