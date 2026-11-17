package com.circulation.prototype_eng.client.gui;

import com.circulation.prototype_eng.api.client.research.ResearchIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Captures registered icons before applying the overview's CSS color effects in the caller's canvas transform.
 */
@SideOnly(Side.CLIENT)
public final class ResearchNodeIconRenderer {
    private static final int TEXTURE_SIZE = 32;
    private static final String VERTEX_SHADER = """
        #version 120
        varying vec2 textureCoords;
        void main() {
            gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
            textureCoords = gl_MultiTexCoord0.st;
        }
        """;
    private static final String FRAGMENT_SHADER = """
        #version 120
        varying vec2 textureCoords;
        uniform sampler2D sourceTexture;
        uniform vec3 effects;
        void main() {
            vec4 color = texture2D(sourceTexture, textureCoords);
            float gray = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            vec3 rgb = mix(color.rgb, vec3(gray), effects.y);
            rgb = min(rgb * effects.z, vec3(color.a));
            gl_FragColor = vec4(rgb * effects.x, color.a * effects.x);
        }
        """;

    private final State state = new State();
    private final FloatBuffer effects = BufferUtils.createFloatBuffer(3);
    private Framebuffer framebuffer;
    private int program;
    private int effectsLocation;
    private int textureLocation;

    public void beginBatch() {
        state.capture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
    }

    public void endBatch() {
        try {
            state.restore();
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * Opacity and grayscale range from zero to one; brightness is the CSS brightness multiplier.
     * Draw between {@link #beginBatch()} and {@link #endBatch()}; delete when the owning GUI closes.
     */
    public void draw(Minecraft minecraft, ResearchIcon icon, float x, float y, float size,
                     float opacity, float grayscale, float brightness) {
        try {
            if (!OpenGlHelper.isFramebufferEnabled() || !OpenGlHelper.areShadersSupported()) {
                drawDirect(minecraft, icon, x, y, size);
                return;
            }
            ensureResources();
            captureIcon(minecraft, icon);
            state.restoreTarget();
            composite(x, y, size, opacity, grayscale, brightness);
        } finally {
            state.restore();
        }
    }

    private void ensureResources() {
        if (program == 0) {
            int vertex = compile(OpenGlHelper.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = 0;
            int created = 0;
            try {
                fragment = compile(OpenGlHelper.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
                created = OpenGlHelper.glCreateProgram();
                OpenGlHelper.glAttachShader(created, vertex);
                OpenGlHelper.glAttachShader(created, fragment);
                OpenGlHelper.glLinkProgram(created);
                if (OpenGlHelper.glGetProgrami(created, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE)
                    throw new IllegalStateException(OpenGlHelper.glGetProgramInfoLog(created, 32768));
                program = created;
                effectsLocation = OpenGlHelper.glGetUniformLocation(program, "effects");
                textureLocation = OpenGlHelper.glGetUniformLocation(program, "sourceTexture");
            } finally {
                OpenGlHelper.glDeleteShader(vertex);
                if (fragment != 0) OpenGlHelper.glDeleteShader(fragment);
                if (program == 0 && created != 0) OpenGlHelper.glDeleteProgram(created);
            }
        }
        if (framebuffer == null) {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            framebuffer = new Framebuffer(TEXTURE_SIZE, TEXTURE_SIZE, true);
            framebuffer.setFramebufferColor(0, 0, 0, 0);
            framebuffer.setFramebufferFilter(GL11.GL_NEAREST);
        }
    }

    private static int compile(int type, String source) {
        int shader = OpenGlHelper.glCreateShader(type);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes).flip();
        OpenGlHelper.glShaderSource(shader, buffer);
        OpenGlHelper.glCompileShader(shader);
        if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = OpenGlHelper.glGetShaderInfoLog(shader, 32768);
            OpenGlHelper.glDeleteShader(shader);
            throw new IllegalStateException(log);
        }
        return shader;
    }

    private void captureIcon(Minecraft minecraft, ResearchIcon icon) {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, 16, 16, 0, 1000, 3000);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.loadIdentity();
            GlStateManager.translate(0, 0, -2000);
            OpenGlHelper.glUseProgram(0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            framebuffer.framebufferClear();
            framebuffer.bindFramebuffer(true);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableFog();
            GlStateManager.disableCull();
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, .1f);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
            icon.draw(minecraft, 0, 0);
        } finally {
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(state.matrixMode);
        }
    }

    private void composite(float x, float y, float size, float opacity, float grayscale, float brightness) {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        // The transparent capture contains premultiplied RGB, so opacity scales RGB and alpha together.
        GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.colorMask(true, true, true, true);
        framebuffer.bindFramebufferTexture();
        OpenGlHelper.glUseProgram(program);
        OpenGlHelper.glUniform1i(textureLocation, 0);
        effects.clear();
        effects.put(opacity).put(grayscale).put(brightness).flip();
        OpenGlHelper.glUniform3(effectsLocation, effects);
        var tessellator = Tessellator.getInstance();
        var buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y + size, 0).tex(0, 0).endVertex();
        buffer.pos(x + size, y + size, 0).tex(1, 0).endVertex();
        buffer.pos(x + size, y, 0).tex(1, 1).endVertex();
        buffer.pos(x, y, 0).tex(0, 1).endVertex();
        tessellator.draw();
    }

    private static void drawDirect(Minecraft minecraft, ResearchIcon icon, float x, float y, float size) {
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(size / 16, size / 16, 1);
            icon.draw(minecraft, 0, 0);
        } finally {
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
        }
    }

    public void delete() {
        if (framebuffer == null && program == 0) return;
        state.capture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (framebuffer != null) {
                framebuffer.deleteFramebuffer();
                framebuffer = null;
            }
            if (program != 0) {
                OpenGlHelper.glDeleteProgram(program);
                program = 0;
            }
        } finally {
            try {
                state.restore();
            } finally {
                GL11.glPopAttrib();
            }
        }
    }

    /**
     * Native attribute restoration also needs matching Minecraft state caches after icon callbacks.
     */
    private static final class State {
        private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
        private final IntBuffer scissorBox = BufferUtils.createIntBuffer(16);
        private final FloatBuffer values = BufferUtils.createFloatBuffer(16);
        private final ByteBuffer masks = BufferUtils.createByteBuffer(16);
        private int matrixMode, activeTexture, defaultTexture, lightTexture, shader, drawFramebuffer, readFramebuffer, renderbuffer;
        private int alphaFunc, srcRgb, dstRgb, srcAlpha, dstAlpha, depthFunc, shadeModel, materialFace, materialMode, lights;
        private boolean framebufferSupported, splitFramebuffer, shadersSupported, defaultEnabled, lightEnabled, scissor;
        private boolean lighting, blend, depth, depthMask, alpha, cull, fog, normalize, rescaleNormal, colorMaterial;
        private float alphaRef, r, g, b, a, clearR, clearG, clearB, clearA, lightX, lightY;
        private double clearDepth;

        private void capture() {
            matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            defaultTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            defaultEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            lightTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            lightEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(activeTexture);
            shadersSupported = OpenGlHelper.areShadersSupported();
            shader = !shadersSupported ? 0 : OpenGlHelper.openGL21 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
                : ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);
            framebufferSupported = OpenGlHelper.framebufferSupported;
            if (framebufferSupported) {
                var capabilities = GLContext.getCapabilities();
                splitFramebuffer = capabilities.OpenGL30 || capabilities.GL_ARB_framebuffer_object;
                drawFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
                readFramebuffer = splitFramebuffer ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) : drawFramebuffer;
                renderbuffer = GL11.glGetInteger(EXTFramebufferObject.GL_RENDERBUFFER_BINDING_EXT);
            }
            viewport.clear();
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
            scissorBox.clear();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissorBox);
            scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            fog = GL11.glIsEnabled(GL11.GL_FOG);
            normalize = GL11.glIsEnabled(GL11.GL_NORMALIZE);
            rescaleNormal = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
            colorMaterial = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL);
            materialFace = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_FACE);
            materialMode = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_PARAMETER);
            lights = 0;
            for (int i = 0; i < 8; i++) if (GL11.glIsEnabled(GL11.GL_LIGHT0 + i)) lights |= 1 << i;
            alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            shadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
            values.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, values);
            r = values.get(0);
            g = values.get(1);
            b = values.get(2);
            a = values.get(3);
            values.clear();
            GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, values);
            clearR = values.get(0);
            clearG = values.get(1);
            clearB = values.get(2);
            clearA = values.get(3);
            clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
            masks.clear();
            GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, masks);
            lightX = OpenGlHelper.lastBrightnessX;
            lightY = OpenGlHelper.lastBrightnessY;
        }

        private void restoreTarget() {
            if (framebufferSupported) {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, drawFramebuffer);
                if (splitFramebuffer) OpenGlHelper.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, renderbuffer);
            }
            GlStateManager.viewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            GL11.glScissor(scissorBox.get(0), scissorBox.get(1), scissorBox.get(2), scissorBox.get(3));
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        private void restore() {
            restoreTarget();
            if (shadersSupported) OpenGlHelper.glUseProgram(shader);
            if (lighting) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
            if (blend) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
            if (depth) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
            if (alpha) GlStateManager.enableAlpha();
            else GlStateManager.disableAlpha();
            if (cull) GlStateManager.enableCull();
            else GlStateManager.disableCull();
            if (fog) GlStateManager.enableFog();
            else GlStateManager.disableFog();
            if (normalize) GlStateManager.enableNormalize();
            else GlStateManager.disableNormalize();
            if (rescaleNormal) GlStateManager.enableRescaleNormal();
            else GlStateManager.disableRescaleNormal();
            if (colorMaterial) GlStateManager.enableColorMaterial();
            else GlStateManager.disableColorMaterial();
            GlStateManager.colorMaterial(materialFace, materialMode);
            for (int i = 0; i < 8; i++) {
                if ((lights & 1 << i) != 0) GlStateManager.enableLight(i);
                else GlStateManager.disableLight(i);
            }
            GlStateManager.alphaFunc(alphaFunc, alphaRef);
            GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GlStateManager.depthFunc(depthFunc);
            GlStateManager.depthMask(depthMask);
            GlStateManager.shadeModel(shadeModel);
            GlStateManager.clearColor(clearR, clearG, clearB, clearA);
            GlStateManager.clearDepth(clearDepth);
            GlStateManager.colorMask(masks.get(0) != 0, masks.get(1) != 0, masks.get(2) != 0, masks.get(3) != 0);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(defaultTexture);
            if (defaultEnabled) GlStateManager.enableTexture2D();
            else GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.bindTexture(lightTexture);
            if (lightEnabled) GlStateManager.enableTexture2D();
            else GlStateManager.disableTexture2D();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
            GlStateManager.setActiveTexture(activeTexture);
            GlStateManager.matrixMode(matrixMode);
            GlStateManager.resetColor();
            GlStateManager.color(r, g, b, a);
        }
    }

}
