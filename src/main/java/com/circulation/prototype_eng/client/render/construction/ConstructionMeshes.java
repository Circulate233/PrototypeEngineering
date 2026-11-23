package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.MeshKind;
import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.Section;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.localX;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.localY;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.localZ;

final class ConstructionMeshes {
    private static final int EDGE_GRID = ConstructionAnimation.EDGE + 1;
    private static final double INSET = 0.002;
    private final IntOpenHashSet edges = new IntOpenHashSet();
    private @Nullable BufferBuilder buffer;
    private final WorldVertexBufferUploader uploader = new WorldVertexBufferUploader();
    private int uploads;
    private int uploadedBytes;
    private int draws;

    /** Compile a union of occupied cells, including adjacency across batch boundaries. */
    @Nullable Mesh build(ConstructionAnimation animation, Section batch, MeshKind kind, boolean lines, boolean vbo) {
        var output = buffer();
        int mode = lines ? GL11.GL_LINES : GL11.GL_QUADS;
        output.begin(mode, DefaultVertexFormats.POSITION_TEX);
        try {
            var cells = batch.visit(kind);
            for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1)) {
                if (!batch.occupied(cell, kind)) continue;
                int x = localX(cell), y = localY(cell), z = localZ(cell);
                if (lines) {
                    for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                        outline(animation, batch, kind, output, x, y + i, z + j, 0);
                        outline(animation, batch, kind, output, x + i, y, z + j, 1);
                        outline(animation, batch, kind, output, x + i, y + j, z, 2);
                    }
                } else {
                    double hiX = x + 1.0, hiY = y + 1.0, hiZ = z + 1.0;
                    if (!animation.occupied(batch, x - 1, y, z, kind)) quad(output, x - INSET, y, z, x - INSET, y, hiZ, x - INSET, hiY, hiZ, x - INSET, hiY, z);
                    if (!animation.occupied(batch, x + 1, y, z, kind)) quad(output, hiX + INSET, y, z, hiX + INSET, hiY, z, hiX + INSET, hiY, hiZ, hiX + INSET, y, hiZ);
                    if (!animation.occupied(batch, x, y - 1, z, kind)) quad(output, x, y - INSET, z, hiX, y - INSET, z, hiX, y - INSET, hiZ, x, y - INSET, hiZ);
                    if (!animation.occupied(batch, x, y + 1, z, kind)) quad(output, x, hiY + INSET, z, x, hiY + INSET, hiZ, hiX, hiY + INSET, hiZ, hiX, hiY + INSET, z);
                    if (!animation.occupied(batch, x, y, z - 1, kind)) quad(output, x, y, z - INSET, x, hiY, z - INSET, hiX, hiY, z - INSET, hiX, y, z - INSET);
                    if (!animation.occupied(batch, x, y, z + 1, kind)) quad(output, x, y, hiZ + INSET, hiX, y, hiZ + INSET, hiX, hiY, hiZ + INSET, x, hiY, hiZ + INSET);
                }
            }
        } finally {
            edges.clear();
            output.finishDrawing();
        }
        return upload(output, mode, vbo);
    }

    Mesh frame(int sizeX, int sizeY, int sizeZ, boolean vbo) {
        var output = buffer();
        output.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_TEX);
        for (int first = 0; first < 2; first++) for (int second = 0; second < 2; second++) {
            line(output, 0, first * sizeY, second * sizeZ, sizeX, first * sizeY, second * sizeZ);
            line(output, first * sizeX, 0, second * sizeZ, first * sizeX, sizeY, second * sizeZ);
            line(output, first * sizeX, second * sizeY, 0, first * sizeX, second * sizeY, sizeZ);
        }
        output.finishDrawing();
        var result = upload(output, GL11.GL_LINES, vbo);
        if (result == null) throw new IllegalStateException("Construction frame produced no edges");
        return result;
    }

    private void outline(ConstructionAnimation animation, Section batch, MeshKind kind, BufferBuilder output, int x, int y, int z, int axis) {
        int edge = ((x * EDGE_GRID + y) * EDGE_GRID + z) * 3 + axis;
        if (!edges.add(edge)) return;
        int centerX = axis == 0 ? x : x - 1;
        int centerY = axis == 2 ? y - 1 : axis == 0 ? y - 1 : y;
        int centerZ = axis == 2 ? z : z - 1;
        int firstX = axis == 0 ? 0 : 1;
        int firstY = axis == 0 ? 1 : 0;
        int secondY = axis == 2 ? 1 : 0;
        int secondZ = axis == 2 ? 0 : 1;
        int mask = 0;
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            int cellX = centerX + a * firstX;
            int cellY = centerY + a * firstY + b * secondY;
            int cellZ = centerZ + b * secondZ;
            if (animation.occupied(batch, cellX, cellY, cellZ, kind)) mask |= 1 << (a * 2 + b);
        }
        if (mask == 0 || mask == 15) return;
        int owner = Integer.numberOfTrailingZeros(mask);
        int ownerX = centerX + (owner >> 1) * firstX;
        int ownerY = centerY + (owner >> 1) * firstY + (owner & 1) * secondY;
        int ownerZ = centerZ + (owner & 1) * secondZ;
        if ((ownerX | ownerY | ownerZ) < 0 || (ownerX | ownerY | ownerZ) >= ConstructionAnimation.EDGE) return;
        line(output, x, y, z, x + (axis == 0 ? 1 : 0), y + (axis == 1 ? 1 : 0), z + (axis == 2 ? 1 : 0));
    }

    private static void quad(BufferBuilder buffer, double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4) {
        vertex(buffer, x1, y1, z1);
        vertex(buffer, x2, y2, z2);
        vertex(buffer, x3, y3, z3);
        vertex(buffer, x4, y4, z4);
    }

    private static void line(BufferBuilder buffer, int x1, int y1, int z1, int x2, int y2, int z2) {
        vertex(buffer, x1, y1, z1);
        vertex(buffer, x2, y2, z2);
    }

    private static void vertex(BufferBuilder buffer, double x, double y, double z) {
        buffer.pos(x, y, z).tex(0.5, y).endVertex();
    }

    private BufferBuilder buffer() {
        if (buffer == null) buffer = new BufferBuilder(1 << 16);
        return buffer;
    }

    private @Nullable Mesh upload(BufferBuilder output, int mode, boolean vbo) {
        int count = output.getVertexCount();
        try {
            if (count == 0) return null;
            Mesh created;
            if (vbo) {
                var vertices = new VertexBuffer(DefaultVertexFormats.POSITION_TEX);
                try {
                    vertices.bufferData(output.getByteBuffer());
                } catch (RuntimeException | Error failure) {
                    vertices.deleteGlBuffers();
                    throw failure;
                }
                created = new Mesh(vertices, 0, mode);
            } else {
                int display = GLAllocation.generateDisplayLists(1);
                try {
                    GlStateManager.glNewList(display, GL11.GL_COMPILE);
                    try {
                        uploader.draw(output);
                    } finally {
                        GlStateManager.glEndList();
                    }
                } catch (RuntimeException | Error failure) {
                    GLAllocation.deleteDisplayLists(display);
                    throw failure;
                }
                created = new Mesh(null, display, mode);
            }
            uploads++;
            uploadedBytes += count * DefaultVertexFormats.POSITION_TEX.getSize();
            return created;
        } finally {
            output.reset();
        }
    }

    void resetCounters() {
        draws = 0;
        uploads = 0;
        uploadedBytes = 0;
    }

    Metrics metrics() {
        return new Metrics(draws, uploads, uploadedBytes);
    }

    boolean canUpload(int maxMeshes, int maxBytes) {
        return uploads < maxMeshes && uploadedBytes < maxBytes;
    }

    void draw(@Nullable Mesh mesh) {
        if (mesh == null) return;
        mesh.draw();
        draws++;
    }

    record Metrics(int draws, int uploads, int uploadedBytes) {
    }

    record Mesh(@Nullable VertexBuffer vertices, int displayList, int mode) {
        void draw() {
            if (vertices == null) {
                GlStateManager.callList(displayList);
                return;
            }
            vertices.bindBuffer();
            int stride = DefaultVertexFormats.POSITION_TEX.getSize();
            GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, stride, 0);
            GlStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, stride, 3 * Float.BYTES);
            vertices.drawArrays(mode);
        }

        void delete() {
            if (vertices != null) vertices.deleteGlBuffers();
            else GLAllocation.deleteDisplayLists(displayList);
        }
    }

    static void beginDraw() {
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
    }

    static void endDraw() {
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }
}
