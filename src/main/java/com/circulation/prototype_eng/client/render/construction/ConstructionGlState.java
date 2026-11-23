package com.circulation.prototype_eng.client.render.construction;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

import java.nio.FloatBuffer;

record ConstructionGlState(boolean atlasTexture, boolean lightmapTexture, int activeTexture, int arrayBuffer,
                           int matrixMode, boolean lighting, boolean cull, boolean blend, boolean depth, boolean depthMask, boolean alpha,
                           int depthFunc, int blendSrc, int blendDst, int alphaSrc, int alphaDst,
                           boolean polygonOffset, float factor, float units,
                           float lineWidth, float red, float green, float blue, float opacity) {
    static ConstructionGlState capture(FloatBuffer values) {
        values.clear();
        GlStateManager.getFloat(GL11.GL_CURRENT_COLOR, values);
        float red = values.get(0), green = values.get(1), blue = values.get(2), opacity = values.get(3);
        int active = GlStateManager.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lightmap = enabled(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        return new ConstructionGlState(enabled(GL11.GL_TEXTURE_2D), lightmap, active,
            OpenGlHelper.vboSupported ? GlStateManager.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) : 0,
            GlStateManager.glGetInteger(GL11.GL_MATRIX_MODE), enabled(GL11.GL_LIGHTING), enabled(GL11.GL_CULL_FACE),
            enabled(GL11.GL_BLEND), enabled(GL11.GL_DEPTH_TEST), enabled(GL11.GL_DEPTH_WRITEMASK), enabled(GL11.GL_ALPHA_TEST),
            GlStateManager.glGetInteger(GL11.GL_DEPTH_FUNC),
            GlStateManager.glGetInteger(GL14.GL_BLEND_SRC_RGB), GlStateManager.glGetInteger(GL14.GL_BLEND_DST_RGB),
            GlStateManager.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GlStateManager.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
            enabled(GL11.GL_POLYGON_OFFSET_FILL), getFloat(GL11.GL_POLYGON_OFFSET_FACTOR, values), getFloat(GL11.GL_POLYGON_OFFSET_UNITS, values),
            getFloat(GL11.GL_LINE_WIDTH, values), red, green, blue, opacity);
    }

    private static boolean enabled(int parameter) {
        return GlStateManager.glGetInteger(parameter) != 0;
    }

    private static float getFloat(int parameter, FloatBuffer output) {
        output.clear();
        GlStateManager.getFloat(parameter, output);
        return output.get(0);
    }

    static void prepare() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(false);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.glLineWidth(1.5F);
    }

    void restore() {
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        if (atlasTexture) GlStateManager.enableTexture2D();
        else GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        if (lightmapTexture) GlStateManager.enableTexture2D();
        else GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(activeTexture);
        if (OpenGlHelper.vboSupported) OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arrayBuffer);
        GlStateManager.matrixMode(matrixMode);
        if (lighting) GlStateManager.enableLighting();
        else GlStateManager.disableLighting();
        if (cull) GlStateManager.enableCull();
        else GlStateManager.disableCull();
        if (blend) GlStateManager.enableBlend();
        else GlStateManager.disableBlend();
        if (alpha) GlStateManager.enableAlpha();
        else GlStateManager.disableAlpha();
        if (depth) GlStateManager.enableDepth();
        else GlStateManager.disableDepth();
        GlStateManager.depthFunc(depthFunc);
        GlStateManager.depthMask(depthMask);
        GlStateManager.tryBlendFuncSeparate(blendSrc, blendDst, alphaSrc, alphaDst);
        if (polygonOffset) GlStateManager.enablePolygonOffset();
        else GlStateManager.disablePolygonOffset();
        GlStateManager.doPolygonOffset(factor, units);
        GlStateManager.glLineWidth(lineWidth);
        GlStateManager.color(red, green, blue, opacity);
    }
}
