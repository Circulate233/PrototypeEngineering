package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.widgets.Scrollbar;
import net.minecraft.util.ResourceLocation;

/** Original PM button frames and matching wide/narrow scrollbar source slices. */
public final class HatchGuiSprites {
    private static final ResourceLocation MODES = GuiAtlas.texture("io_mode");
    private static final ResourceLocation SCROLLBARS = GuiAtlas.texture("hatch_scrollbars");
    private static final Scrollbar.Style RESOURCE_SCROLL = Scrollbar.Style.sheet(
        SCROLLBARS, SCROLLBARS, 0, 0, 39, 0, 12, 15, 64, 80);
    private static final Scrollbar.Style[] PATTERN_SCROLLS = {
        patternScroll(42), patternScroll(29), patternScroll(55)
    };

    private HatchGuiSprites() {
    }

    private static Scrollbar.Style patternScroll(int top) {
        return Scrollbar.Style.sheet(SCROLLBARS, SCROLLBARS, 0, top, 24, top, 7, 12, 64, 80);
    }

    public static Scrollbar.Style resourceScrollStyle() {
        return RESOURCE_SCROLL;
    }

    public static Scrollbar.Style patternScrollStyle(int kind) {
        return PATTERN_SCROLLS[kind];
    }

    static void scrollbar(int x, int y, boolean thin, HatchScrollbar.ThumbState state) {
        int column = switch (state) {
            case NORMAL -> 0;
            case HOVER -> 1;
            case PRESSED -> 2;
            case DISABLED -> 3;
        };
        GuiAtlas.draw(SCROLLBARS, x, y, column * (thin ? 8 : 13), thin ? 16 : 0,
            thin ? 7 : 12, thin ? 12 : 15, 64, 80);
    }

    public static void button(int x, int y, int spriteY, boolean enabled, boolean hovered, boolean pressed) {
        int u = !enabled ? 54 : pressed ? 36 : hovered ? 18 : 0;
        GuiAtlas.draw(MODES, x, y, u, spriteY, 17, 18, 256, 256);
    }
}
