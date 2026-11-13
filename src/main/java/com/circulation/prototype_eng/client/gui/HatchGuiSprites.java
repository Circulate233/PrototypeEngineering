package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.widgets.Scrollbar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/** Original PM controls with native AE disabled-scrollbar artwork. */
public final class HatchGuiSprites {
    private static final ResourceLocation HATCH_COLOR_BUTTON = GuiAtlas.texture("hatch_color_button");
    private static final ResourceLocation ME_MODES = GuiAtlas.texture("io_mode");
    private static final ResourceLocation DISABLED_BIG = new ResourceLocation("ae2", "textures/guis/sprites/big_scroller_disabled.png");
    private static final ResourceLocation DISABLED_SMALL = new ResourceLocation("ae2", "textures/guis/sprites/small_scroller_disabled.png");
    private static final Scrollbar.Style[] PATTERN_SCROLLS = {
        patternScroll("input"), patternScroll("catalyst"), patternScroll("output")
    };

    private HatchGuiSprites() {
    }

    private static Scrollbar.Style patternScroll(String name) {
        return Scrollbar.Style.create(GuiAtlas.texture("gui_bus/pattern_" + name + "_scroll"), DISABLED_SMALL, 7, 12);
    }

    public static Scrollbar.Style patternScrollStyle(int kind) {
        return PATTERN_SCROLLS[kind];
    }

    static void scrollbar(int x, int y, boolean thin, HatchScrollbar.ThumbState state) {
        if (state == HatchScrollbar.ThumbState.DISABLED) {
            var texture = thin ? DISABLED_SMALL : DISABLED_BIG;
            int width = thin ? 7 : 12;
            var minecraft = Minecraft.getMinecraft();
            minecraft.getTextureManager().bindTexture(texture);
            GlStateManager.color(1, 1, 1, 1);
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, width, 15,
                width, thin ? 12 : 15, width, 15);
            return;
        }
        int column = switch (state) {
            case NORMAL -> 0;
            case HOVER -> 1;
            case PRESSED -> 2;
            case DISABLED -> throw new AssertionError("Disabled handle uses the native AE sprite");
        };
        GuiAtlas.state(x, y, (thin ? 161 : 146) + column * (thin ? 8 : 13), thin ? 17 : 1,
            thin ? 7 : 12, thin ? 12 : 15);
    }

    static void hatchColorButton(int x, int y, boolean hovered, boolean pressed) {
        // Include the fixed two-pixel lower backing/shadow present beneath the original IO buttons.
        GuiAtlas.draw(HATCH_COLOR_BUTTON, x, y, pressed ? 28 : hovered ? 14 : 0, 0,
            14, 16, 42, 16);
    }

    static void meColorButton(int x, int y, boolean hovered, boolean pressed) {
        GuiAtlas.draw(ME_MODES, x, y, pressed ? 36 : hovered ? 18 : 0, 0,
            17, 18, 256, 256);
    }
}
