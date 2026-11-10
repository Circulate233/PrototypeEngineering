package com.circulation.prototype_eng.client.preview;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;

import java.math.RoundingMode;
import java.text.DecimalFormat;

/** PM ItemDisplayWidget's NumberFormat.AMOUNT_TEXT and bottom-right scaled amount overlay. */
final class PreviewAmountText {
    private static final DecimalFormat FORMAT = new DecimalFormat("0.###");
    private static final String[] PREFIXES = {"", "k", "M", "G"};

    static {
        FORMAT.setRoundingMode(RoundingMode.DOWN);
    }

    private PreviewAmountText() {
    }

    static String format(int amount) {
        int prefix = 0;
        double divisor = 1;
        if (amount >= 10_000) {
            prefix = 1;
            divisor = 1_000;
            while (prefix < PREFIXES.length - 1 && amount >= divisor * 1_000) {
                prefix++;
                divisor *= 1_000;
            }
        }
        double value = amount * (1 / divisor);
        int places = 4 - (prefix == 0 ? 0 : 1);
        if (value % 1 > 0) places -= (int) Math.floor(Math.log10(value)) + 1;
        FORMAT.setMaximumFractionDigits(Math.max(0, places));
        return FORMAT.format(value) + PREFIXES[prefix];
    }

    static void draw(FontRenderer font, int amount) {
        if (amount <= 1) return;
        String text = format(amount);
        float scale = text.length() > 2 ? Math.min(1, 18f / font.getStringWidth(text)) : 1;
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(18 - font.getStringWidth(text) * scale, 18 - font.FONT_HEIGHT * scale, 0);
            GlStateManager.scale(scale, scale, 1);
            font.drawString(text, 0, 0, 0xffffff, true);
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.enableDepth();
        }
    }
}
