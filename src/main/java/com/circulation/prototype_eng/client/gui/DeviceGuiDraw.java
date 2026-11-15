package com.circulation.prototype_eng.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

final class DeviceGuiDraw {
    static final int TEXT = 0x404040, SOFT = 0x5a5d72, DARK = 0x413f54, LINE = 0x696d88;
    static final int PANEL = 0x9a9fb4, SLOT = 0xadb0c4, LIGHT = 0xf2f2f2, MUTED = 0x878fa5;
    static final int GOOD = 0x1f6b2a, WAIT = 0x7a5200, BAD = 0xa02020;

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

    static void text(FontRenderer font, String text, int x, int y, int width, int color) {
        if (font.getStringWidth(text) > width)
            text = font.trimStringToWidth(text, width - font.getStringWidth("…")) + "…";
        GuiLabel.draw(font, text, x, y, width, 10, color, false);
    }

    static void right(FontRenderer font, String text, int right, int y, int width, int height, int color) {
        int textWidth = font.getStringWidth(text);
        float scale = Math.min(1f, Math.min((float) width / Math.max(1, textWidth),
            (float) height / (font.FONT_HEIGHT - 1)));
        GuiLabel.draw(font, text, right - textWidth * scale, y, width, height, color, false);
    }

    static int statusWidth(FontRenderer font, String text) {
        return font.getStringWidth(text) + 14;
    }

    static void status(FontRenderer font, String text, int x, int y, Tone tone) {
        int width = statusWidth(font, text);
        bordered(x, y, width, 10, tone.background, tone.border);
        rect(x + 4, y + 4, 3, 3, tone.text);
        GuiLabel.draw(font, text, x + 10, y, width - 12, 10, tone.text, false);
    }

    static void progress(int x, int y, int width, int height, double fraction, int color) {
        if (height <= 2) {
            rect(x, y, width, height, SLOT);
            int fill = (int) (width * Math.clamp(fraction, 0, 1));
            if (fill > 0) rect(x, y, fill, height, color);
            return;
        }
        rect(x, y, width, height, LINE);
        int innerHeight = height - 2;
        rect(x + 1, y + 1, width - 2, innerHeight, SLOT);
        int fill = (int) ((width - 2) * Math.clamp(fraction, 0, 1));
        if (fill > 0) rect(x + 1, y + 1, fill, innerHeight, color);
    }

    static void card(int x, int y, int width, int height, boolean selected, boolean hovered) {
        bordered(x, y, width, height, selected ? LIGHT : hovered ? 0xb6baca : 0xa3a7bb,
            selected ? LINE : 0x8d92a8);
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
