package com.circulation.prototype_eng.client.gui;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Scissor rectangles in the caller's current GUI transform, including embedded JEI pages. */
public final class GuiClip {
    private final FloatBuffer model = BufferUtils.createFloatBuffer(16), projection = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer first = BufferUtils.createFloatBuffer(3), second = BufferUtils.createFloatBuffer(3);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4), previous = BufferUtils.createIntBuffer(4);

    public void draw(float x, float y, float width, float height, Runnable content) {
        model.clear(); projection.clear(); viewport.clear(); first.clear(); second.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        if (!GLU.gluProject(x, y, 0, model, projection, viewport, first)
            || !GLU.gluProject(x + width, y + height, 0, model, projection, viewport, second)) return;
        int left = (int) Math.floor(Math.min(first.get(0), second.get(0)));
        int bottom = (int) Math.floor(Math.min(first.get(1), second.get(1)));
        int right = (int) Math.ceil(Math.max(first.get(0), second.get(0)));
        int top = (int) Math.ceil(Math.max(first.get(1), second.get(1)));
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            previous.clear();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, previous);
            left = Math.max(left, previous.get(0)); bottom = Math.max(bottom, previous.get(1));
            right = Math.min(right, previous.get(0) + previous.get(2)); top = Math.min(top, previous.get(1) + previous.get(3));
        }
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
            content.run();
        } finally { GL11.glPopAttrib(); }
    }
}
