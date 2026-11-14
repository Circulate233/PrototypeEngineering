package com.circulation.prototype_eng.client.gui;

import ae2.api.behaviors.ContainerItemStrategies;
import ae2.api.client.AEKeyRendering;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AmountFormat;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.container.slot.AppEngSlot;
import ae2.core.localization.ButtonToolTips;
import ae2.core.localization.Tooltips;
import ae2.helpers.InventoryAction;
import ae2.text.TextComponentItemStack;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.container.ContainerResourceHatch;
import com.circulation.prototype_eng.localization.GuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * PM's ItemHatchGUI/ItemIOHatchGUI, with AE's native generic resource slots.
 */
public final class GuiResourceHatch extends AEBaseGui<ContainerResourceHatch> {
    private final ResourceLocation background;
    private final HatchScrollbar inputScrollbar;
    private final HatchScrollbar outputScrollbar;
    private ResourceColorSelector colorSelector;
    private long gestureRevision;
    private long lastShiftRevision, lastShiftTime;
    private int lastShiftSlot = -1;
    private boolean lastShiftInput;
    private AEKey lastShiftKey;
    private int pressedResource = -1, pressedLogical = -1, pressedButton = -1;
    private long pressedRevision;
    private boolean skipResourceTap;
    private long lastTapTime, lastTapRevision;
    private int lastTapLogical = -1, lastTapButton = -1;
    private boolean lastTapInput;

    public GuiResourceHatch(ContainerResourceHatch container, InventoryPlayer player) {
        super(container, player);
        xSize = container.guiWidth;
        ySize = container.guiHeight;
        boolean mixed = container.mode() == HatchMode.MIXED;
        background = container.background;
        inputScrollbar = new HatchScrollbar(mixed, container.scrollbars && container.mode().hasInput());
        outputScrollbar = new HatchScrollbar(mixed, container.scrollbars && container.mode().hasOutput());
        widgets.add("inputScrollbar", inputScrollbar);
        widgets.add("outputScrollbar", outputScrollbar);
        if (container.hasColorGrouping()) {
            colorSelector = new ResourceColorSelector(() -> container.color, container::setResourceColor,
                ResourceColorSelector.Style.HATCH);
            colorSelector.setPosition(new Point(4, 49));
            widgets.add("resourceColor", colorSelector);
        }
        if (container.hasAutomationSettings()) {
            widgets.add("autoInput", new HatchToggleButton(true,
                () -> container.autoInput, container.mode().hasInput(), container::toggleInput, new Point(4, 13)));
            widgets.add("autoOutput", new HatchToggleButton(false,
                () -> container.autoOutput, container.mode().hasOutput(), container::toggleOutput, new Point(4, 31)));
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        inputScrollbar.configure(container.inputLeft, container.resourceTop, container.columns, container.visibleRows,
            container.mode().hasInput() ? container.inputRows() : 0);
        outputScrollbar.configure(container.outputLeft, container.resourceTop, container.columns, container.visibleRows,
            container.mode().hasOutput() ? container.outputRows() : 0);
        updateResourceSlots();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        inputScrollbar.configure(container.inputLeft, container.resourceTop, container.columns, container.visibleRows, container.inputRows());
        outputScrollbar.configure(container.outputLeft, container.resourceTop, container.columns, container.visibleRows, container.outputRows());
        var mouse = new Point(Mouse.getX() * width / mc.displayWidth - guiLeft,
            height - Mouse.getY() * height / mc.displayHeight - 1 - guiTop);
        inputScrollbar.frame(mouse);
        outputScrollbar.frame(mouse);
        updateResourceSlots();
    }

    private void updateResourceSlots() {
        container.presentation.beginLayout(container.snapshot.revision());
        container.requestView((int) (inputScrollbar.scroll() / 18), (int) (outputScrollbar.scroll() / 18));
        for (int index3 = 0; index3 < container.inventorySlots.size(); index3++) {
            Slot slot = container.inventorySlots.get(index3);
            if (container.isResourceSlot(slot.slotNumber)) {
                var resource = (AppEngSlot) slot;
                boolean input = container.isInputSlot(slot.slotNumber);
                int cell = container.resourceCell(slot.slotNumber);
                long top = container.resourceTop + (container.snapshot.side(input).firstSlot() / container.columns
                    + (long) cell / container.columns) * 18
                    - (input ? inputScrollbar : outputScrollbar).scroll();
                boolean visible = container.logicalSlot(slot.slotNumber) >= 0
                    && top + 18 > container.resourceTop && top < container.resourceTop + container.visibleRows * 18L;
                resource.setActive(visible);
                slot.xPos = visible ? (input ? container.inputLeft : container.outputLeft) + cell % container.columns * 18 + 1 : -9999;
                slot.yPos = visible ? (int) top + 1 : -9999;
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

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(background, x, y, 0, 0, xSize, ySize, container.textureWidth, 256);
        if (container.mode().hasInput()) drawSlotFrames(x, y, true);
        if (container.mode().hasOutput()) drawSlotFrames(x, y, false);
    }

    private void drawSlotFrames(int x, int y, boolean input) {
        int left = input ? container.inputLeft : container.outputLeft;
        beginClip(left);
        try {
            for (int index1 = 0; index1 < container.inventorySlots.size(); index1++) {
                var slot = container.inventorySlots.get(index1);
                if (container.isResourceSlot(slot.slotNumber) && container.isInputSlot(slot.slotNumber) == input && slot.isEnabled())
                    GuiAtlas.state(x + slot.xPos - 1, y + slot.yPos - 1, 73, 0, 18, 18);
            }
        } finally {
            GL11.glPopAttrib();
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
        beginClip(container.isInputSlot(slot.slotNumber) ? container.inputLeft : container.outputLeft);
        try {
            super.drawSlot(slot);
        } finally {
            GL11.glPopAttrib();
            restoreStateForVanillaItemRender();
        }
    }

    @Override
    protected boolean isPointInRegion(int left, int top, int regionWidth, int regionHeight, int pointX, int pointY) {
        if (regionWidth == 16 && regionHeight == 16) {
            for (int index2 = 0; index2 < container.inventorySlots.size(); index2++) {
                var slot = container.inventorySlots.get(index2);
                if (container.isResourceSlot(slot.slotNumber) && slot.xPos == left && slot.yPos == top
                    && (pointY < guiTop + container.resourceTop || pointY >= guiTop + container.resourceTop + container.visibleRows * 18))
                    return false;
            }
        }
        return super.isPointInRegion(left, top, regionWidth, regionHeight, pointX, pointY);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        GuiLabel.draw(fontRenderer, container.getGuiTitle().getFormattedText(), 29, 8, titleWidth(), 10, 0x404040, false);
        if (container.mode().hasInput())
            GuiAtlas.inset(container.inputLeft, container.resourceTop, container.columns * 18, container.visibleRows * 18);
        if (container.mode().hasOutput())
            GuiAtlas.inset(container.outputLeft, container.resourceTop, container.columns * 18, container.visibleRows * 18);
    }

    @Override
    protected boolean isHovering(Slot slot, int mouseX, int mouseY) {
        if (container.isResourceSlot(slot.slotNumber)
            && (mouseY < guiTop + container.resourceTop || mouseY >= guiTop + container.resourceTop + container.visibleRows * 18))
            return false;
        return super.isHovering(slot, mouseX, mouseY);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (isPointInRegion(29, 8, titleWidth(), 10, mouseX, mouseY))
            drawHoveringText(List.of(Objects.requireNonNull(container.getGuiTitle()).getFormattedText()), mouseX, mouseY);
        container.presentation.completeFrame();
    }

    private int titleWidth() {
        return container.mode() == HatchMode.MIXED && container.visibleRows <= 4 ? 100 : 138;
    }

    @Override
    protected void renderHoveredToolTip(int mouseX, int mouseY) {
        if (colorSelector != null && colorSelector.blocksTooltips(mouseX - guiLeft, mouseY - guiTop)) return;
        var slot = findSlot(mouseX, mouseY);
        if (slot == null || !container.isResourceSlot(slot.slotNumber)) {
            super.renderHoveredToolTip(mouseX, mouseY);
            return;
        }
        var resource = (AppEngSlot) slot;
        var key = resource.getGenericDisplayKey();
        var carried = getCarriedItem();
        var emptying = container.isInputSlot(slot.slotNumber) ? ContainerItemStrategies.getEmptyingAction(carried) : null;
        if (emptying != null) {
            drawTooltipWithHeader(mouseX, mouseY, Tooltips.getEmptyingTooltip(ButtonToolTips.StoreAction, carried, emptying));
            return;
        }
        if (key != null && !(key instanceof AEItemKey) && !carried.isEmpty()) {
            var context = ContainerItemStrategies.findCarriedContextForKey(key, playerInventory.player, container);
            if (context != null && context.insert(key, resource.getGenericDisplayAmount(), Actionable.SIMULATE) > 0) {
                drawTooltipWithHeader(mouseX, mouseY, List.of(Tooltips.muted(ButtonToolTips.FillAction.text(
                    Tooltips.getMouseButtonText(0), Tooltips.of(TextComponentItemStack.of(carried))))));
                return;
            }
        }
        var lines = new ObjectArrayList<ITextComponent>();
        if (key != null) {
            if (AEKeyRendering.get(key.getType()) != null) lines.addAll(AEKeyRendering.getTooltip(key));
            else lines.add(key.getDisplayName());
            lines.add(new TextComponentString(""));
            if (container.mode() == HatchMode.MIXED)
                lines.add((container.isInputSlot(slot.slotNumber) ? GuiText.Input : GuiText.Output).text());
            lines.add(GuiText.ResourceTypes.text(container.usedSlots(container.isInputSlot(slot.slotNumber)),
                container.typeSlots(container.isInputSlot(slot.slotNumber))));
            lines.add(GuiText.ResourceStored.text(key.formatAmount(resource.getGenericDisplayAmount(), AmountFormat.FULL),
                key.formatAmount(container.capacity(slot.slotNumber, key), AmountFormat.FULL)));
        }
        if (!lines.isEmpty()) {
            if (key != null) drawKeyTooltipWithImages(mouseX, mouseY, key, lines);
            else drawTooltipWithHeader(mouseX, mouseY, lines);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (colorSelector != null && colorSelector.expanded()) {
            pressedResource = -1;
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }
        gestureRevision = container.presentation.gestureRevision();
        var slot = findSlot(mouseX, mouseY);
        if ((button == 0 || button == 1) && slot instanceof AppEngSlot resource && container.isResourceSlot(slot.slotNumber)) {
            pressedResource = slot.slotNumber;
            pressedLogical = container.logicalSlot(pressedResource);
            pressedButton = button;
            pressedRevision = gestureRevision;
            boolean clone = mc.gameSettings.keyBindPickBlock.isActiveAndMatches(button - 100);
            skipResourceTap = clone || isShiftKeyDown() && getCarriedItem().isEmpty()
                && (resource.getGenericDisplayKey() != null || recentShiftSlot(slot.slotNumber));
            if (skipResourceTap)
                handleMouseClick(slot, slot.slotNumber, button, clone ? ClickType.CLONE : ClickType.QUICK_MOVE);
            return;
        }
        pressedResource = -1;
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        if (pressedResource < 0) {
            super.mouseReleased(mouseX, mouseY, button);
            return;
        }
        int index = pressedResource;
        pressedResource = -1;
        var slot = findSlot(mouseX, mouseY);
        if (skipResourceTap || button != pressedButton || slot == null || slot.slotNumber != index
            || !slot.isEnabled() || !container.resourceViewReady() || pressedRevision != container.snapshot.revision()
            || pressedLogical != container.logicalSlot(index)) return;
        gestureRevision = pressedRevision;
        long now = System.currentTimeMillis();
        boolean input = container.isInputSlot(index);
        boolean doubleTap = lastTapButton == button && lastTapLogical == pressedLogical && lastTapInput == input
            && lastTapRevision == gestureRevision && lastTapTime > 0 && now - lastTapTime <= 250;
        if (doubleTap) {
            lastTapTime = 0;
            lastTapButton = -1;
            container.doubleClickResource(index, button);
        } else {
            lastTapTime = now;
            lastTapButton = button;
            lastTapLogical = pressedLogical;
            lastTapInput = input;
            lastTapRevision = gestureRevision;
            handleMouseClick(slot, index, button, ClickType.PICKUP);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (pressedResource < 0) super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        gestureRevision = container.presentation.gestureRevision();
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected boolean checkHotbarKeys(int keyCode) {
        var slot = getSlotUnderMouse();
        if (slot != null && container.isResourceSlot(slot.slotNumber) && getCarriedItem().isEmpty()) {
            for (int hotbar = 0; hotbar < 9; hotbar++) {
                if (mc.gameSettings.keyBindsHotbar[hotbar].isActiveAndMatches(keyCode)) {
                    if (!container.isPlayerInventorySlotLocked(hotbar))
                        handleMouseClick(slot, slot.slotNumber, hotbar, ClickType.SWAP);
                    return true;
                }
            }
            return false;
        }
        return super.checkHotbarKeys(keyCode);
    }

    private boolean recentShiftSlot(int index) {
        return lastShiftKey instanceof AEItemKey && System.currentTimeMillis() - lastShiftTime <= 250
            && lastShiftRevision == container.snapshot.revision() && lastShiftInput == container.isInputSlot(index)
            && lastShiftSlot == container.logicalSlot(index);
    }

    @Override
    protected void handleMouseClick(Slot slot, int slotId, int button, @NotNull ClickType click) {
        if (container.isResourceSlot(slotId) && (!container.resourceViewReady()
            || gestureRevision != container.snapshot.revision() || slot == null || !slot.isEnabled())) return;
        if (click == ClickType.CLONE && container.isResourceSlot(slotId) && slot instanceof AppEngSlot resource
            && resource.isEnabled() && resource.getGenericDisplayKey() instanceof AEItemKey) {
            send(InventoryAction.CREATIVE_DUPLICATE, slotId);
            return;
        }
        if (container.isResourceSlot(slotId) && Keyboard.isKeyDown(Keyboard.KEY_SPACE)) {
            send(InventoryAction.MOVE_REGION, slotId);
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
        if (container.isResourceSlot(slotId)) {
            boolean matching = false;
            if (click == ClickType.QUICK_MOVE && button == 0 && slot instanceof AppEngSlot resource) {
                var key = resource.getGenericDisplayKey();
                long now = System.currentTimeMillis();
                matching = recentShiftSlot(slotId) && (key == null || key.equals(lastShiftKey));
                lastShiftKey = matching ? null : key;
                lastShiftTime = now;
                lastShiftRevision = container.snapshot.revision();
                lastShiftInput = container.isInputSlot(slotId);
                lastShiftSlot = container.logicalSlot(slotId);
            }
            container.clickResource(slotId, button, click, matching);
        } else {
            super.handleMouseClick(slot, slotId, button, click);
        }
    }

    private void send(InventoryAction action, int slot) {
        container.resourceAction(action, slot);
    }
}
