package com.circulation.prototype_eng.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Pixel slices of the original PM tool atlases.
 */
final class StructureToolDraw {
    private StructureToolDraw() {
    }

    static void image(String path, int x, int y, int width, int height) {
        GuiAtlas.draw(GuiAtlas.texture(path), x, y, 0, 0, width, height, width, height);
    }

    static void horizontal(String path, int x, int y, int width, int height, int sourceWidth, int left, int middle, int rightStart, int right) {
        var texture = GuiAtlas.texture(path);
        GuiAtlas.draw(texture, x, y, 0, 0, left, height, sourceWidth, height);
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        GlStateManager.color(1, 1, 1, 1);
        Gui.drawScaledCustomSizeModalRect(x + left, y, middle, 0, 1, height, width - left - right, height, sourceWidth, height);
        GuiAtlas.draw(texture, x + width - right, y, rightStart, 0, right, height, sourceWidth, height);
    }
}
