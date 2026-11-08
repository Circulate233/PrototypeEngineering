package com.circulation.prototype_eng.client.gui;

import java.util.List;
import java.util.Collection;
import ae2.client.gui.AEBaseGui;
import ae2.container.AEBaseContainer;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import com.circulation.prototype_eng.client.preview.PreviewBackRequest;
import com.circulation.prototype_eng.container.StructurePreviewContainer;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.InventoryPlayer;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/** Concrete AE2 screen. A machine sub-screen uses exactly the parent's original container and window. */
public final class StructurePreviewGui extends AEBaseGui<AEBaseContainer> {
    private final StructurePreviewView view;
    private final PreviewScreenTransitions.Session navigation;
    private StructurePreviewPanel panel;
    private final PreviewBackRequest backRequest = new PreviewBackRequest(StructurePreviewGui::buttonsReleased, this::back);
    public StructurePreviewGui(InventoryPlayer inventory, StructurePreviewView view, PreviewScreenTransitions.Session navigation) {
        this(new StructurePreviewContainer(inventory), inventory, view, navigation);
    }
    public StructurePreviewGui(AEBaseGui<?> parent, StructurePreviewView view, PreviewScreenTransitions.Session navigation) {
        this(parent.getContainer(), parent.getContainer().getPlayerInventory(), view, navigation);
    }
    private StructurePreviewGui(AEBaseContainer container, InventoryPlayer inventory, StructurePreviewView view, PreviewScreenTransitions.Session navigation) {
        super(container, inventory); this.view = view; this.navigation = navigation; xSize = 384; ySize = 280;
        // A canceled native InitPre still leaves a displayed GUI. Its resources and local input
        // must already exist; a canceled/replaced GuiOpen releases this panel through Session.
        panel = new StructurePreviewPanel(view);
    }
    @Override public void initGui() {
        if (!navigation.initCurrent(this)) { releasePreview(); return; }
        xSize = Math.clamp(width - 8, 174, 384); ySize = Math.clamp(height - 8, 200, 280);
        super.initGui();
        if (!navigation.initCurrent(this)) { releasePreview(); return; }
        if (panel == null || panel.closed()) panel = new StructurePreviewPanel(view);
        buttonList.add(new GuiButton(0, guiLeft + xSize - 50, guiTop - 19, 48, 18, StructurePreviewText.Back.getLocal()));
    }
    @Override public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {}
    @Override public void drawFG(int x, int y, int mouseX, int mouseY) {
        if (!navigation.viewerCurrent(this) || panel == null || panel.closed()) return;
        panel.draw(xSize, ySize, mouseX - guiLeft, mouseY - guiTop, mc.getRenderPartialTicks(), false);
    }
    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (!navigation.viewerCurrent(this) || panel == null || panel.closed()) return;
        super.drawScreen(mouseX, mouseY, partialTicks);
        var tooltip = panel.tooltip(mouseX - guiLeft, mouseY - guiTop);
        if (!tooltip.isEmpty()) drawHoveringText(tooltip, mouseX, mouseY);
    }
    @Override protected void actionPerformed(GuiButton button) { if (button.id == 0) backRequest.mousePress(); }
    private void back() {
        navigation.back();
    }
    @Override protected void keyTyped(char typed, int key) {
        if (!navigation.viewerCurrent(this)) return;
        if (key == Keyboard.KEY_ESCAPE || mc.gameSettings.keyBindInventory.isActiveAndMatches(key)) {
            backRequest.keyPress();
            return;
        }
        if (inputCurrent()) panel.key(typed, key);
    }
    @Override protected void mouseClicked(int x, int y, int button) {
        if (!navigation.viewerCurrent(this)) return;
        if (inputCurrent() && panel.press(x - guiLeft, y - guiTop, button)) return;
        if (button != 0) return;
        for (var control : buttonList) if (control.mousePressed(mc, x, y)) {
            control.playPressSound(mc.getSoundHandler()); actionPerformed(control); return;
        }
    }
    @Override protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (inputCurrent()) panel.drag(x - guiLeft, y - guiTop);
    }
    @Override protected void mouseReleased(int x, int y, int button) {
        if (!navigation.viewerCurrent(this)) return;
        if (inputCurrent()) panel.release(x - guiLeft, y - guiTop, button);
        for (var control : buttonList) control.mouseReleased(x, y);
        backRequest.finishReleased();
    }
    @Override public void handleMouseInput() throws IOException {
        if (!navigation.viewerCurrent(this)) return;
        int wheel = Mouse.getEventDWheel();
        int x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (inputCurrent() && wheel != 0 && panel.wheel(x - guiLeft, y - guiTop, wheel)) return;
        super.handleMouseInput();
    }
    @Override public void onGuiClosed() {
        releasePreview();
    }
    @Override protected void handleMouseClick(Slot slot, int id, int button, @NotNull ClickType type) { /* This screen owns no inventory operation. */ }
    @Override public boolean handleAeMouseWheelInput() {
        if (!inputCurrent()) return false;
        int x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        return panel.wheel(x - guiLeft, y - guiTop, Mouse.getEventDWheel());
    }
    @Override public boolean MT_isMouseTweaksDisabled() { return true; }
    @Override public Slot MT_getSlotUnderMouse() { return null; }
    @Override public @NotNull Collection<? extends Slot> getHEISlots(Object ingredient) { return List.of(); }
    @Override public void updateScreen() {
        if (!navigation.viewerCurrent(this)) {
            releasePreview();
            if (mc.currentScreen == this) navigation.back();
            return;
        }
        if (panel != null) panel.tick();
        backRequest.finishReleased();
    }
    private boolean inputCurrent() { return navigation.viewerCurrent(this) && panel != null && panel.inputCurrent(); }
    private static boolean buttonsReleased() {
        for (int button = 0; button < Mouse.getButtonCount(); button++) if (Mouse.isButtonDown(button)) return false;
        return true;
    }
    public void releasePreview() { if (panel != null) panel.close(); }
    public void suspendNativeSlots() { navigation.suspendNativeSlots(this); }
}
