package com.circulation.prototype_eng.client.render.construction;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

import java.nio.FloatBuffer;

record ConstructionGlState(boolean atlasTexture, int atlasBinding, boolean lightmapTexture, int activeTexture,
                           int clientActiveTexture, int arrayBuffer, boolean lighting, boolean cull,
                           boolean blend, boolean depthMask, boolean alpha, int alphaFunc, float alphaRef,
                           int blendSrc, int blendDst, int alphaSrc, int alphaDst,
                           float lineWidth, float red, float green, float blue, float opacity) {
    static ConstructionGlState capture(FloatBuffer color) {
        color.clear();
        GlStateManager.getFloat(GL11.GL_CURRENT_COLOR, color);
        float red = color.get(0), green = color.get(1), blue = color.get(2), opacity = color.get(3);
        int active = GlStateManager.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lightmap = enabled(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        return new ConstructionGlState(enabled(GL11.GL_TEXTURE_2D), GlStateManager.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), lightmap,
            active, GlStateManager.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE), OpenGlHelper.vboSupported ? GlStateManager.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) : 0,
            enabled(GL11.GL_LIGHTING), enabled(GL11.GL_CULL_FACE), enabled(GL11.GL_BLEND), enabled(GL11.GL_DEPTH_WRITEMASK), enabled(GL11.GL_ALPHA_TEST),
            GlStateManager.glGetInteger(GL11.GL_ALPHA_TEST_FUNC), getFloat(GL11.GL_ALPHA_TEST_REF, color),
            GlStateManager.glGetInteger(GL14.GL_BLEND_SRC_RGB), GlStateManager.glGetInteger(GL14.GL_BLEND_DST_RGB),
            GlStateManager.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GlStateManager.glGetInteger(GL14.GL_BLEND_DST_ALPHA), getFloat(GL11.GL_LINE_WIDTH, color),
            red, green, blue, opacity);
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
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
        GlStateManager.depthMask(false);
        GlStateManager.glLineWidth(1.5F);
    }

    void restore() {
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(atlasBinding);
        if (atlasTexture) GlStateManager.enableTexture2D();
        else GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        if (lightmapTexture) GlStateManager.enableTexture2D();
        else GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(activeTexture);
        OpenGlHelper.setClientActiveTexture(clientActiveTexture);
        if (OpenGlHelper.vboSupported) OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arrayBuffer);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        if (lighting) GlStateManager.enableLighting();
        else GlStateManager.disableLighting();
        if (cull) GlStateManager.enableCull();
        else GlStateManager.disableCull();
        if (blend) GlStateManager.enableBlend();
        else GlStateManager.disableBlend();
        if (alpha) GlStateManager.enableAlpha();
        else GlStateManager.disableAlpha();
        GlStateManager.alphaFunc(alphaFunc, alphaRef);
        GlStateManager.depthMask(depthMask);
        GlStateManager.tryBlendFuncSeparate(blendSrc, blendDst, alphaSrc, alphaDst);
        GlStateManager.glLineWidth(lineWidth);
        GlStateManager.color(red, green, blue, opacity);
    }
}
