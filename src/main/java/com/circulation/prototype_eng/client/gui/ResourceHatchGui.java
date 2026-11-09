package com.circulation.prototype_eng.client.gui;

import ae2.api.behaviors.ContainerItemStrategies;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.container.slot.AppEngSlot;
import ae2.core.network.InitNetwork;
import ae2.core.network.serverbound.InventoryActionPacket;
import ae2.helpers.InventoryAction;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import org.jetbrains.annotations.NotNull;
import java.util.Objects;
import java.io.IOException;
import net.minecraft.client.gui.GuiButton;
import com.circulation.prototype_eng.api.storage.HatchMode;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import java.util.List;
import ae2.api.stacks.AmountFormat;
import ae2.api.client.AEKeyRendering;
import ae2.core.localization.Tooltips;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
/** PM's ItemHatchGUI/ItemIOHatchGUI, with AE's native generic resource slots. */
public final class ResourceHatchGui extends AEBaseGui<ResourceHatchContainer> {
    private final ResourceLocation background;
    private final HatchScrollbar inputScrollbar;
    private final HatchScrollbar outputScrollbar;

    public ResourceHatchGui(ResourceHatchContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = container.guiWidth;
        ySize = container.guiHeight;
        boolean mixed = container.mode() == HatchMode.MIXED;
        String folder = mixed ? "gui_material_io_hatch" : "gui_material_hatch";
        background = GuiAtlas.texture(folder + "/" + folder + "_" + container.tier().level());
        inputScrollbar = new HatchScrollbar(mixed);
        outputScrollbar = new HatchScrollbar(mixed);
        widgets.add("inputScrollbar", inputScrollbar);
        widgets.add("outputScrollbar", outputScrollbar);
        widgets.add("autoInput", new HatchToggleButton(100, 0, 0, true,
            () -> container.autoInput, container.hasAutomationSettings() && container.mode().hasInput()), new Point(4, 13));
        widgets.add("autoOutput", new HatchToggleButton(101, 0, 0, false,
            () -> container.autoOutput, container.hasAutomationSettings() && container.mode().hasOutput()), new Point(4, 31));
    }

    @Override public void initGui() {
        super.initGui();
        inputScrollbar.configure(26, container.resourceTop, container.columns, container.visibleRows,
            container.mode().hasInput() ? container.inputRows() : 0);
        outputScrollbar.configure(container.outputLeft, container.resourceTop, container.columns, container.visibleRows,
            container.mode().hasOutput() ? container.outputRows() : 0);
        updateResourceSlots();
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 100) container.toggleInput();
        else if (button.id == 101) container.toggleOutput();
        else super.actionPerformed(button);
    }

    @Override protected boolean isHovering(Slot slot, int mouseX, int mouseY) {
        if (container.isResourceSlot(slot.slotNumber)
            && (mouseY < guiTop + container.resourceTop || mouseY >= guiTop + container.resourceTop + container.visibleRows * 18)) return false;
        return super.isHovering(slot, mouseX, mouseY);
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (isPointInRegion(29, 8, titleWidth(), 10, mouseX, mouseY))
            drawHoveringText(List.of(Objects.requireNonNull(container.getGuiTitle()).getFormattedText()), mouseX, mouseY);
    }

    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        var mouse = new Point(Mouse.getX() * width / mc.displayWidth - guiLeft,
            height - Mouse.getY() * height / mc.displayHeight - 1 - guiTop);
        inputScrollbar.frame(mouse);
        outputScrollbar.frame(mouse);
        updateResourceSlots();
    }

    private void updateResourceSlots() {
        for (Slot slot : container.inventorySlots) {
            if (container.isResourceSlot(slot.slotNumber) && slot instanceof AppEngSlot resource) {
                boolean input = container.isInputSlot(slot.slotNumber);
                int top = container.resourceTop + (slot.getSlotIndex() / container.columns) * 18
                    - (input ? inputScrollbar : outputScrollbar).scroll();
                boolean visible = top + 18 > container.resourceTop && top < container.resourceTop + container.visibleRows * 18;
                resource.setActive(visible);
                slot.xPos = visible ? (input ? 26 : container.outputLeft) + slot.getSlotIndex() % container.columns * 18 + 1 : -9999;
                slot.yPos = visible ? top + 1 : -9999;
            }
        }
    }

    private void beginClip(int left) {
        int scale = new ScaledResolution(mc).getScaleFactor();
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((guiLeft + left) * scale,
            mc.displayHeight - (guiTop + container.resourceTop + container.visibleRows * 18) * scale,
            container.columns * 18 * scale, container.visibleRows * 18 * scale);
    }

    @Override public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(background, x, y, 0, 0, xSize, ySize, container.textureWidth, 256);
        if (container.mode().hasInput()) drawSlotFrames(x, y, true);
        if (container.mode().hasOutput()) drawSlotFrames(x, y, false);
    }

    private void drawSlotFrames(int x, int y, boolean input) {
        int left = input ? 26 : container.outputLeft;
        beginClip(left);
        try {
            for (var slot : container.inventorySlots) {
                if (container.isResourceSlot(slot.slotNumber) && container.isInputSlot(slot.slotNumber) == input && slot.isEnabled())
                    GuiAtlas.state(x + slot.xPos - 1, y + slot.yPos - 1, 73, 0, 18, 18);
            }
        } finally { GL11.glPopAttrib(); }
    }

    @Override protected void drawSlot(@NotNull Slot slot) {
        if (!container.isResourceSlot(slot.slotNumber)) { super.drawSlot(slot); return; }
        beginClip(container.isInputSlot(slot.slotNumber) ? 26 : container.outputLeft);
        try { super.drawSlot(slot); } finally { GL11.glPopAttrib(); }
    }

    @Override protected boolean isPointInRegion(int left, int top, int regionWidth, int regionHeight, int pointX, int pointY) {
        if (regionWidth == 16 && regionHeight == 16) {
            for (var slot : container.inventorySlots) {
                if (container.isResourceSlot(slot.slotNumber) && slot.xPos == left && slot.yPos == top
                    && (pointY < guiTop + container.resourceTop || pointY >= guiTop + container.resourceTop + container.visibleRows * 18)) return false;
            }
        }
        return super.isPointInRegion(left, top, regionWidth, regionHeight, pointX, pointY);
    }

    @Override public void drawFG(int x, int y, int mouseX, int mouseY) {
        GuiLabel.draw(fontRenderer, Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 29, 8, titleWidth(), 10, 0x404040, false);
        if (container.mode().hasInput()) GuiAtlas.inset(26, container.resourceTop, container.columns * 18, container.visibleRows * 18);
        if (container.mode().hasOutput()) GuiAtlas.inset(container.outputLeft, container.resourceTop, container.columns * 18, container.visibleRows * 18);
    }

    private int titleWidth() { return container.mode() == HatchMode.MIXED && container.visibleRows <= 4 ? 100 : 138; }

    @Override protected void renderHoveredToolTip(int mouseX, int mouseY) {
        var slot = findSlot(mouseX, mouseY);
        if (slot == null || !container.isResourceSlot(slot.slotNumber) || !(slot instanceof AppEngSlot resource)) {
            super.renderHoveredToolTip(mouseX, mouseY);
            return;
        }
        var lines = new ObjectArrayList<ITextComponent>();
        var key = resource.getGenericDisplayKey();
        if (key != null) {
            if (AEKeyRendering.get(key.getType()) != null) lines.addAll(AEKeyRendering.getTooltip(key));
            else lines.add(key.getDisplayName());
            lines.add(new TextComponentString(""));
            lines.add(GuiText.ResourceTypes.text(container.usedTypes(container.isInputSlot(slot.slotNumber)),
                container.typeSlots(container.isInputSlot(slot.slotNumber))));
            lines.add(GuiText.ResourceStored.text(key.formatAmount(resource.getGenericDisplayAmount(), AmountFormat.FULL),
                key.formatAmount(container.capacity(slot.slotNumber, key), AmountFormat.FULL)));
        }
        var carried = getCarriedItem();
        var emptying = container.isInputSlot(slot.slotNumber) ? ContainerItemStrategies.getEmptyingAction(carried) : null;
        if (emptying != null) lines.add(GuiText.EmptyContainer.text(Tooltips.getMouseButtonText(1), emptying.description()));
        if (key != null && !(key instanceof AEItemKey) && !carried.isEmpty()) {
            var context = ContainerItemStrategies.findCarriedContextForKey(key, playerInventory.player, container);
            if (context != null && context.insert(key, resource.getGenericDisplayAmount(), Actionable.SIMULATE) > 0)
                lines.add(GuiText.FillContainer.text(Tooltips.getMouseButtonText(0)));
        }
        if (!lines.isEmpty()) {
            if (key != null) drawKeyTooltipWithImages(mouseX, mouseY, key, lines);
            else drawTooltipWithHeader(mouseX, mouseY, lines);
        }
    }

    @Override protected void handleMouseClick(Slot slot, int slotId, int button, @NotNull ClickType click) {
        if (click == ClickType.CLONE && container.isResourceSlot(slotId) && slot instanceof AppEngSlot resource
            && resource.isEnabled() && resource.getGenericDisplayKey() instanceof AEItemKey) {
            send(InventoryAction.CREATIVE_DUPLICATE, slotId);
            return;
        }
        if (container.isResourceSlot(slotId) && slot instanceof AppEngSlot resource && resource.isEnabled()
            && (click == ClickType.PICKUP || click == ClickType.QUICK_MOVE) && !getCarriedItem().isEmpty()) {
            if (button == 1 && container.isInputSlot(slotId) && ContainerItemStrategies.getEmptyingAction(getCarriedItem()) != null) {
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
