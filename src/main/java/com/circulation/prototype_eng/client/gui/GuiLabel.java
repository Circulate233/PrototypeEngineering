package com.circulation.prototype_eng.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Bounded single-line text, with the same eight-pixel visible font height as PM's text widgets.
 */
public final class GuiLabel {
    private GuiLabel() {
    }

    public static void draw(FontRenderer font, String text, float left, float top, int width, int height, int color, boolean shadow) {
        draw(font, text, left, top, width, height, color, shadow, false);
    }

    public static void drawCentered(FontRenderer font, String text, float left, float top, int width, int height, int color, boolean shadow) {
        draw(font, text, left, top, width, height, color, shadow, true);
    }

    private static void draw(FontRenderer font, String text, float left, float top, int width, int height,
                             int color, boolean shadow, boolean centered) {
        int textWidth = font.getStringWidth(text) + (shadow ? 1 : 0);
        float scale = Math.min(1f, Math.min((float) width / Math.max(1, textWidth), (float) height / Math.max(1, font.FONT_HEIGHT - 1)));
        float textLeft = centered ? left + (width - textWidth * scale) / 2f : left;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(textLeft, top + (height - (font.FONT_HEIGHT - 1) * scale) / 2f, 0);
            GlStateManager.scale(scale, scale, 1);
            font.drawString(text, 0, 0, color, shadow);
        } finally {
            GlStateManager.popMatrix();
        }
    }
}
