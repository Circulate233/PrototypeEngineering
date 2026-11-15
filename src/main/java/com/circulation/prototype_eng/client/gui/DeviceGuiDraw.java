package com.circulation.prototype_eng.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * Shared drawing for the network center, computer and research station screens. Coordinates are GUI-relative pixels.
 */
final class DeviceGuiDraw {
    static final int TEXT = 0x404040, SOFT = 0x5a5d72, DARK = 0x413f54, LINE = 0x696d88;
    static final int PANEL = 0x9a9fb4, SLOT = 0xadb0c4, LIGHT = 0xf2f2f2, MUTED = 0x878fa5;
    static final int CARD = 0xa3a7bb, CARD_BORDER = 0x8d92a8, HOVER = 0xb6baca;
    static final int GOOD = 0x1f6b2a, WAIT = 0x7a5200, BAD = 0xa02020;
    static final int LINE_HEIGHT = 10;
    private static final String ELLIPSIS = "...";

    enum Tone {
        GOOD(DeviceGuiDraw.GOOD, 0xc2d4c5, 0x8fb095),
        WAIT(DeviceGuiDraw.WAIT, 0xddd2b6, 0xb9a77a),
        BAD(DeviceGuiDraw.BAD, 0xdcc3c6, 0xc09398);

        final int text, background, border;

        Tone(int text, int background, int border) {
            this.text = text;
            this.background = background;
            this.border = border;
        }
    }

    private DeviceGuiDraw() {
    }

    static void panel(int x, int y, int width, int height) {
        rect(x, y, width, height, PANEL);
        GuiAtlas.inset(x, y, width, height);
    }

    static void rect(int x, int y, int width, int height, int color) {
        Gui.drawRect(x, y, x + width, y + height, 0xff000000 | color);
    }

    static void bordered(int x, int y, int width, int height, int background, int border) {
        rect(x, y, width, height, border);
        rect(x + 1, y + 1, width - 2, height - 2, background);
    }

    static boolean fits(FontRenderer font, String text, int width) {
        return font.getStringWidth(text) <= width;
    }

    /**
     * Left-aligned single line; text wider than {@code width} ends in an ellipsis; check it with {@link #fits} before showing the full text as a tooltip.
     */
    static void text(FontRenderer font, String text, int x, int y, int width, int color) {
        if (width <= 0) return;
        if (!fits(font, text, width))
            text = font.trimStringToWidth(text, Math.max(0, width - font.getStringWidth(ELLIPSIS))) + ELLIPSIS;
        font.drawString(text, x, y + 1, color);
    }

    /**
     * Right-aligned single line ending at {@code right}; narrower than {@code width} it keeps its natural size.
     */
    static void right(FontRenderer font, String text, int right, int y, int width, int color) {
        int textWidth = Math.min(width, font.getStringWidth(text));
        text(font, text, right - textWidth, y, textWidth, color);
    }

    static void centered(FontRenderer font, String text, int x, int y, int width, int height, int color) {
        GuiLabel.drawCentered(font, text, x, y, width, height, color, false);
    }

    static int statusWidth(FontRenderer font, String text) {
        return font.getStringWidth(text) + 14;
    }

    static void status(FontRenderer font, String text, int x, int y, Tone tone) {
        int width = statusWidth(font, text);
        bordered(x, y, width, LINE_HEIGHT, tone.background, tone.border);
        rect(x + 4, y + 4, 3, 3, tone.text);
        font.drawString(text, x + 10, y + 1, tone.text);
    }

    /**
     * Two-pixel bars have no border; taller bars receive a one-pixel line border.
     */
    static void progress(int x, int y, int width, int height, double fraction, int color) {
        int inset = height <= 2 ? 0 : 1;
        if (inset > 0) rect(x, y, width, height, LINE);
        rect(x + inset, y + inset, width - inset * 2, height - inset * 2, SLOT);
        int fill = (int) ((width - inset * 2) * Math.clamp(fraction, 0, 1));
        if (fill > 0) rect(x + inset, y + inset, fill, height - inset * 2, color);
    }

    static void card(int x, int y, int width, int height, boolean selected, boolean hovered) {
        bordered(x, y, width, height, selected ? LIGHT : hovered ? HOVER : CARD, selected ? LINE : CARD_BORDER);
    }

    static void slot(int x, int y) {
        int size = 18;
        rect(x, y, size, size, SLOT);
        rect(x, y, size, 1, LINE);
        rect(x, y, 1, size, LINE);
        rect(x, y + size - 1, size, 1, LIGHT);
        rect(x + size - 1, y, 1, size, LIGHT);
    }
}
