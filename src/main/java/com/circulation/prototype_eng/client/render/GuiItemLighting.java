package com.circulation.prototype_eng.client.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Restores GUI item lights after AEKey/native renderers disable the individual light sources.
 */
public final class GuiItemLighting {
    private static final int LIGHTING = 1;
    private static final int LIGHT0 = 2;
    private static final int LIGHT1 = 4;
    private static final int COLOR_MATERIAL = 8;
    private static final int RESCALE_NORMAL = 16;
    private static final int NORMALIZE = 32;
    private static final int SMOOTH = 64;

    private GuiItemLighting() {
    }

    public static int capture() {
        return (GL11.glIsEnabled(GL11.GL_LIGHTING) ? LIGHTING : 0)
            | (GL11.glIsEnabled(GL11.GL_LIGHT0) ? LIGHT0 : 0)
            | (GL11.glIsEnabled(GL11.GL_LIGHT1) ? LIGHT1 : 0)
            | (GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL) ? COLOR_MATERIAL : 0)
            | (GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL) ? RESCALE_NORMAL : 0)
            | (GL11.glIsEnabled(GL11.GL_NORMALIZE) ? NORMALIZE : 0)
            | (GL11.glGetInteger(GL11.GL_SHADE_MODEL) == GL11.GL_SMOOTH ? SMOOTH : 0);
    }

    public static void restore(int state) {
        // Reapply directions with the caller's GUI matrix, outside the local item scale/rotation.
        if ((state & (LIGHT0 | LIGHT1)) != 0) RenderHelper.enableGUIStandardItemLighting();
        if ((state & LIGHT0) != 0) GlStateManager.enableLight(0);
        else GlStateManager.disableLight(0);
        if ((state & LIGHT1) != 0) GlStateManager.enableLight(1);
        else GlStateManager.disableLight(1);
        if ((state & COLOR_MATERIAL) != 0) GlStateManager.enableColorMaterial();
        else GlStateManager.disableColorMaterial();
        if ((state & LIGHTING) != 0) GlStateManager.enableLighting();
        else GlStateManager.disableLighting();
        if ((state & RESCALE_NORMAL) != 0) GlStateManager.enableRescaleNormal();
        else GlStateManager.disableRescaleNormal();
        if ((state & NORMALIZE) != 0) GlStateManager.enableNormalize();
        else GlStateManager.disableNormalize();
        GlStateManager.shadeModel((state & SMOOTH) != 0 ? GL11.GL_SMOOTH : GL11.GL_FLAT);
    }
}
