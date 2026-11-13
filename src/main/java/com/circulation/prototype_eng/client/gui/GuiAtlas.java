package com.circulation.prototype_eng.client.gui;

import com.circulation.prototype_eng.Tags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * Native drawing of the original PM GUI images, at their original pixel coordinates.
 */
public final class GuiAtlas {
    public static final ResourceLocation STATES = texture("states");

    private GuiAtlas() {
    }

    public static ResourceLocation texture(String path) {
        return new ResourceLocation(Tags.MOD_ID, "textures/gui/" + path + ".png");
    }

    public static void draw(ResourceLocation texture, int x, int y, int u, int v, int width, int height,
                            int textureWidth, int textureHeight) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        GlStateManager.color(1, 1, 1, 1);
        Gui.drawModalRectWithCustomSizedTexture(x, y, u, v, width, height, textureWidth, textureHeight);
    }

    public static void state(int x, int y, int u, int v, int width, int height) {
        draw(STATES, x, y, u, v, width, height, 256, 256);
    }

    public static void inset(int x, int y, int width, int height) {
        // PM InsetBorderWidget: one pixel, inside the grid, #F2F2F2.
        Gui.drawRect(x, y, x + width, y + 1, 0xfff2f2f2);
        Gui.drawRect(x, y + height - 1, x + width, y + height, 0xfff2f2f2);
        Gui.drawRect(x, y + 1, x + 1, y + height - 1, 0xfff2f2f2);
        Gui.drawRect(x + width - 1, y + 1, x + width, y + height - 1, 0xfff2f2f2);
    }
}
