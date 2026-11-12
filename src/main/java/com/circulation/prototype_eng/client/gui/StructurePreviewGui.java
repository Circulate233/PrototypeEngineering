package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.widgets.SimpleIconButton;
import ae2.container.AEBaseContainer;
import ae2.core.localization.GuiText;
import com.circulation.prototype_eng.client.preview.PreviewBackRequest;
import com.circulation.prototype_eng.client.preview.PreviewNativeGui;
import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import com.circulation.prototype_eng.container.StructurePreviewContainer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/** A preview sub-screen retains its parent's container type and original window. */
public final class StructurePreviewGui<C extends AEBaseContainer> extends AEBaseGui<C> implements PreviewNativeGui {
    private final StructurePreviewView view;
    private final PreviewScreenTransitions.Session navigation;
    private final PreviewBackRequest backRequest = new PreviewBackRequest(StructurePreviewGui::buttonsReleased, this::back);
    private StructurePreviewPanel panel;

    public static StructurePreviewGui<StructurePreviewContainer> standalone(InventoryPlayer inventory, StructurePreviewView view,
                                                                          PreviewScreenTransitions.Session navigation) {
        return new StructurePreviewGui<>(new StructurePreviewContainer(inventory), inventory, view, navigation);
    }

    public StructurePreviewGui(AEBaseGui<C> parent, StructurePreviewView view, PreviewScreenTransitions.Session navigation) {
        this(parent.getContainer(), parent.getContainer().getPlayerInventory(), view, navigation);
    }

    private StructurePreviewGui(C container, InventoryPlayer inventory, StructurePreviewView view, PreviewScreenTransitions.Session navigation) {
        super(container, inventory);
        this.view = view;
        this.navigation = navigation;
        xSize = 184;
        ySize = 220;
        widgets.add("close", new SimpleIconButton(Icon.CLEAR, GuiText.Close.text(), backRequest::mousePress), new Point(164, 4));
        panel = new StructurePreviewPanel(view);
    }

    private static boolean buttonsReleased() {
        for (int button = 0; button < Mouse.getButtonCount(); button++) if (Mouse.isButtonDown(button)) return false;
        return true;
    }

    @Override
    public void initGui() {
        if (!navigation.viewerCurrent(this)) {
            releasePreview();
            return;
        }
        super.initGui();
        if (!navigation.viewerCurrent(this)) {
            releasePreview();
            return;
        }
        if (panel == null || panel.closed()) panel = new StructurePreviewPanel(view);
        navigation.suspendNativeSlots(this);
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        if (!navigation.viewerCurrent(this) || panel == null || panel.closed()) return;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            panel.draw(xSize, ySize, mouseX - guiLeft, mouseY - guiTop, partialTicks, false);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (!navigation.viewerCurrent(this) || panel == null || panel.closed()) return;
        super.drawScreen(mouseX, mouseY, partialTicks);
        var materialSlot = panel.slotAt(mouseX - guiLeft, mouseY - guiTop, 0, 0);
        if (materialSlot != null) {
            boolean rendered = panel.ownsSlot(hoveredSlot);
            hoveredSlot = materialSlot;
            if (!rendered) panel.renderItemTooltip(mouseX, mouseY, mouseX - guiLeft, mouseY - guiTop);
            return;
        }
        if (panel.ownsSlot(hoveredSlot)) hoveredSlot = null;
        for (var control : buttonList) if (control.visible && control.isMouseOver()) return;
        if (panel.renderItemTooltip(mouseX, mouseY, mouseX - guiLeft, mouseY - guiTop)) return;
        var tooltip = panel.tooltip(mouseX - guiLeft, mouseY - guiTop);
        if (!tooltip.isEmpty()) drawHoveringText(tooltip, mouseX, mouseY);
    }

    @Override
    protected void actionPerformed(@NotNull GuiButton button) { /* AE icon button dispatches its release callback. */ }

    private void back() {
        navigation.back();
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (!navigation.viewerCurrent(this)) return;
        if (key == Keyboard.KEY_ESCAPE && panel != null && panel.dismissMaterials()) {
            hoveredSlot = null;
            return;
        }
        if (key == Keyboard.KEY_ESCAPE || mc.gameSettings.keyBindInventory.isActiveAndMatches(key)) {
            backRequest.keyPress();
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (!navigation.viewerCurrent(this)) return;
        // The AE close control owns clicks above the scene at their shared edge.
        if (button == 0 || button == 1) for (var control : buttonList)
            if (control.mousePressed(mc, x, y)) {
                control.playPressSound(mc.getSoundHandler());
                actionPerformed(control);
                return;
            }
        if (inputCurrent()) panel.press(x - guiLeft, y - guiTop, button);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (inputCurrent()) panel.drag(x - guiLeft, y - guiTop);
    }

    @Override
    protected void mouseReleased(int x, int y, int button) {
        if (!navigation.viewerCurrent(this)) return;
        if (inputCurrent()) panel.release(x - guiLeft, y - guiTop, button);
        for (var control : buttonList) control.mouseReleased(x, y);
        backRequest.finishReleased();
    }

    @Override
    public void handleMouseInput() throws IOException {
        if (!navigation.viewerCurrent(this)) return;
        int wheel = Mouse.getEventDWheel();
        int x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (inputCurrent() && wheel != 0 && panel.wheel(x - guiLeft, y - guiTop, wheel)) return;
        super.handleMouseInput();
    }

    @Override
    public void onGuiClosed() {
        releasePreview();
    }

    @Override
    protected void handleMouseClick(Slot slot, int id, int button, @NotNull ClickType type) { /* This screen owns no inventory operation. */ }

    @Override
    public boolean handleAeMouseWheelInput() {
        if (!inputCurrent()) return false;
        int x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        return panel.wheel(x - guiLeft, y - guiTop, Mouse.getEventDWheel());
    }

    @Override
    public boolean MT_isMouseTweaksDisabled() {
        return true;
    }

    @Override
    public Slot MT_getSlotUnderMouse() {
        return null;
    }

    @Override
    protected Slot findSlot(int x, int y) {
        return panel == null ? null : panel.slotAt(x - guiLeft, y - guiTop, 0, 0);
    }

    @Override
    public @NotNull Collection<? extends Slot> getHEISlots(Object ingredient) {
        return List.of();
    }

    @Override
    public void updateScreen() {
        if (!navigation.viewerCurrent(this)) {
            releasePreview();
            if (mc.currentScreen == this) navigation.back();
            return;
        }
        if (panel != null) panel.tick();
        backRequest.finishReleased();
    }

    private boolean inputCurrent() {
        return navigation.viewerCurrent(this) && panel != null && panel.inputCurrent();
    }

    public void releasePreview() {
        if (panel != null) panel.close();
    }

    @Override
    public void prototype_eng$renderItemTooltip(ItemStack stack, int x, int y) {
        renderToolTip(stack, x, y);
    }
}
