package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.Slice;
import com.circulation.prototype_eng.client.render.construction.ConstructionMeshes.Arena;
import com.circulation.prototype_eng.client.render.construction.ConstructionMeshes.Mesh;
import com.circulation.prototype_eng.client.render.construction.ConstructionMeshes.Part;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;

import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.MIN_OPACITY;
import static com.circulation.prototype_eng.client.render.construction.ConstructionMeshes.PAGE_BYTES;
import static com.circulation.prototype_eng.client.render.construction.ConstructionMeshes.STRIDE;

final class ConstructionBatches {
    private static final int OPAQUE = 1;
    private static final int FADING = 2;
    private static final int WIRE = 4;
    private static final int COLOR_STRIDE = STRIDE + Integer.BYTES;
    private static final int MAX_RANGES_PER_PAGE = PAGE_BYTES / (2 * STRIDE);
    private final ObjectArrayList<Arena> arenas = new ObjectArrayList<>();
    private final ByteBuffer colored = BufferUtils.createByteBuffer(65_536 * COLOR_STRIDE);
    private final Ranges ranges = new Ranges();
    private int passes;
    private int draws;
    private boolean multiDraw;

    private static void addShell(Arena arena, Part part, int version) {
        var mesh = part.mesh;
        if (mesh == null || part.version != version || part.opacity <= MIN_OPACITY) return;
        if (part.opacity == 1) {
            arena.drawPasses |= OPAQUE;
            mesh.page().solid.add(mesh);
        } else {
            float opacity = (float) part.opacity;
            if ((arena.drawPasses & FADING) == 0) arena.fadeOpacity = opacity;
            else if (arena.fadeOpacity != opacity) arena.mixedFade = true;
            arena.drawPasses |= FADING;
            mesh.page().fading.add(part);
        }
    }

    private static void color(float opacity) {
        GlStateManager.color(244 / 255F, 248 / 255F, 1, opacity);
    }

    void collect(List<Slice> visible) {
        for (int i = 0; i < visible.size(); i++) {
            var slice = visible.get(i);
            var arena = slice.task.arena;
            int previous = arena.drawPasses;
            addShell(arena, slice.solidMesh, slice.version);
            addShell(arena, slice.fluidMesh, slice.version);
            var wire = slice.wire;
            if (slice.wireOpacity > MIN_OPACITY && wire != null && slice.wireVersion == slice.version) {
                arena.drawPasses |= WIRE;
                arena.wireOpacity = (float) slice.wireOpacity;
                wire.page().wires.add(wire);
            }
            if (previous == 0 && arena.drawPasses != 0) arenas.add(arena);
            passes |= arena.drawPasses;
        }
    }

    boolean empty() {
        return passes == 0;
    }

    int draws() {
        return draws;
    }

    void draw(double x, double y, double z) {
        multiDraw = GLContext.getCapabilities().OpenGL14;
        if ((passes & (OPAQUE | FADING)) != 0) {
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
            if ((passes & OPAQUE) != 0) {
                GlStateManager.disableBlend();
                GlStateManager.depthMask(true);
                color(1);
                drawPass(OPAQUE, x, y, z);
            }
            if ((passes & FADING) != 0) {
                GlStateManager.enableBlend();
                GlStateManager.depthMask(false);
                drawPass(FADING, x, y, z);
            }
            GlStateManager.disablePolygonOffset();
        }
        if ((passes & WIRE) != 0) {
            GlStateManager.enableBlend();
            GlStateManager.depthMask(false);
            drawPass(WIRE, x, y, z);
        }
    }

    private void drawPass(int pass, double x, double y, double z) {
        for (int i = 0; i < arenas.size(); i++) {
            var arena = arenas.get(i);
            if ((arena.drawPasses & pass) == 0) continue;
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(arena.x - x, arena.y - y, arena.z - z);
                if (pass == FADING && arena.mixedFade) {
                    drawMixedFades(arena);
                } else {
                    if (pass == FADING) color(arena.fadeOpacity);
                    else if (pass == WIRE) color(arena.wireOpacity);
                    drawPages(arena, pass);
                }
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    private void drawPages(Arena arena, int pass) {
        for (int p = 0; p < arena.pages.size(); p++) {
            var page = arena.pages.get(p);
            if (pass == FADING) {
                for (int i = 0; i < page.fading.size(); i++)
                    ranges.add(Objects.requireNonNull(page.fading.get(i).mesh));
            } else {
                var meshes = pass == WIRE ? page.wires : page.solid;
                for (int i = 0; i < meshes.size(); i++) ranges.add(meshes.get(i));
            }
            if (ranges.empty()) continue;
            if (page.id != 0) {
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, page.id);
                GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0);
            } else {
                if (OpenGlHelper.vboSupported) OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
                GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, Objects.requireNonNull(page.cpu));
            }
            draws += ranges.draw(pass == WIRE ? GL11.GL_LINES : GL11.GL_QUADS, multiDraw);
        }
    }

    private void drawMixedFades(Arena arena) {
        if (OpenGlHelper.vboSupported) OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);
        try {
            for (int p = 0; p < arena.pages.size(); p++) {
                var page = arena.pages.get(p);
                for (int i = 0; i < page.fading.size(); i++) {
                    var part = page.fading.get(i);
                    appendFade(Objects.requireNonNull(part.mesh), (int) Math.round(part.opacity * 255));
                }
            }
            flushFades();
        } finally {
            GlStateManager.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GlStateManager.resetColor();
        }
    }

    private void appendFade(Mesh mesh, int alpha) {
        if (mesh.count() * COLOR_STRIDE > colored.remaining()) flushFades();
        var vertices = mesh.vertices();
        if (vertices != null) {
            for (int i = 0; i < vertices.length; i += 3)
                putVertex(vertices[i], vertices[i + 1], vertices[i + 2], alpha);
        } else {
            var source = Objects.requireNonNull(mesh.page().cpu);
            int start = mesh.first() * STRIDE, end = start + mesh.count() * STRIDE;
            for (int i = start; i < end; i += STRIDE)
                putVertex(source.getInt(i), source.getInt(i + 4), source.getInt(i + 8), alpha);
        }
    }

    private void putVertex(int x, int y, int z, int alpha) {
        colored.putInt(x).putInt(y).putInt(z).put((byte) 244).put((byte) 248).put((byte) 255).put((byte) alpha);
    }

    private void flushFades() {
        int vertices = colored.position() / COLOR_STRIDE;
        if (vertices == 0) return;
        colored.flip();
        GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, COLOR_STRIDE, colored);
        GlStateManager.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, COLOR_STRIDE, colored.position(STRIDE));
        GlStateManager.glDrawArrays(GL11.GL_QUADS, 0, vertices);
        colored.clear();
        draws++;
    }

    void clear() {
        for (int i = 0; i < arenas.size(); i++) {
            var arena = arenas.get(i);
            for (int p = 0; p < arena.pages.size(); p++) {
                var page = arena.pages.get(p);
                page.solid.clear();
                page.fading.clear();
                page.wires.clear();
            }
            arena.drawPasses = 0;
            arena.mixedFade = false;
        }
        arenas.clear();
        colored.clear();
        ranges.clear();
        passes = 0;
    }

    void beginFrame() {
        draws = 0;
    }

    private static final class Ranges {
        private final IntBuffer starts = BufferUtils.createIntBuffer(MAX_RANGES_PER_PAGE);
        private final IntBuffer counts = BufferUtils.createIntBuffer(MAX_RANGES_PER_PAGE);

        void add(Mesh mesh) {
            int size = starts.position();
            if (size != 0 && starts.get(size - 1) + counts.get(size - 1) == mesh.first()) {
                counts.put(size - 1, counts.get(size - 1) + mesh.count());
            } else {
                starts.put(mesh.first());
                counts.put(mesh.count());
            }
        }

        boolean empty() {
            return starts.position() == 0;
        }

        int draw(int mode, boolean multiDraw) {
            int size = starts.position();
            if (size > 1 && multiDraw) {
                GL14.glMultiDrawArrays(mode, starts.flip(), counts.flip());
                size = 1;
            } else {
                for (int i = 0; i < size; i++) GlStateManager.glDrawArrays(mode, starts.get(i), counts.get(i));
            }
            clear();
            return size;
        }

        void clear() {
            starts.clear();
            counts.clear();
        }
    }
}
