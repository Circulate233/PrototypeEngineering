package com.circulation.prototype_eng.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.jetbrains.annotations.NotNull;

/** Original PM panelCloseButton: the framework's 10x10 button with its tiny cross overlay. */
final class PreviewCloseButton extends GuiButton {
    PreviewCloseButton(int x, int y) { super(0, x, y, 10, 10, ""); }

    @Override public void drawButton(@NotNull Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
        hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        var background = GuiAtlas.texture("native_widgets/" + (hovered ? "mc_button_hovered" : "mc_button"));
        // Original MC_BUTTON is adaptable(2).tiled(): the 6px center crops the 12px source tile.
        int[] target = {0, 2, 8}, source = {0, 2, 14}, size = {2, 6, 2};
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            GuiAtlas.draw(background, x + target[col], y + target[row], source[col], source[row], size[col], size[row], 16, 32);
        GuiAtlas.draw(GuiAtlas.texture("native_widgets/cross_tiny"), x, y, 0, 0, 10, 10, 10, 10);
    }
}
