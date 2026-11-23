package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.Slice;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

final class ConstructionMeshes {
    private static final int MAX_UPLOADS = 12;
    private static final int MAX_UPLOAD_BYTES = 1 << 20;
    private static final int STEP_BATCH = 16;
    private static final int STRIDE = DefaultVertexFormats.POSITION.getSize();
    private final ConstructionSurface surface = new ConstructionSurface();
    private final WorldVertexBufferUploader uploader = new WorldVertexBufferUploader();
    private final BufferBuilder buffer = new BufferBuilder(96 * 1024);
    private final ConstructionSurface.Vertex vertex = (x, y, z) -> buffer.pos(x, y, z).endVertex();
    private @Nullable Work work;
    private int uploads;
    private int uploadedBytes;
    private int draws;

    void beginFrame() {
        uploads = 0;
        uploadedBytes = 0;
        draws = 0;
    }

    boolean preparingOutline() {
        return work != null && work.material == 0 && work.slice.wireOpacity <= ConstructionAnimation.MIN_OPACITY;
    }

    boolean resume(int frame, long deadline, boolean vbo, boolean allowPrefetch) {
        var current = work;
        if (current == null) return true;
        var slice = current.slice;
        double opacity = switch (current.material) {
            case 0 -> slice.wireOpacity;
            case ConstructionSurface.SOLID -> slice.solidMesh.opacity;
            default -> slice.fluidMesh.opacity;
        };
        boolean needed = opacity > ConstructionAnimation.MIN_OPACITY || allowPrefetch && current.material == 0 && slice.prepareWire;
        if (slice.seenFrame != frame || slice.version != current.version || !needed) {
            cancel();
            return true;
        }
        return advance(current, deadline, vbo);
    }

    boolean build(Slice slice, byte material, long deadline, boolean vbo) {
        var part = material == ConstructionSurface.SOLID ? slice.solidMesh : slice.fluidMesh;
        if ((material == 0 ? slice.wireVersion : part.version) == slice.version) return true;
        if (uploads >= MAX_UPLOADS || System.nanoTime() >= deadline) return false;
        var next = new Work(slice, material, part);
        buffer.begin(next.mode, DefaultVertexFormats.POSITION);
        surface.reset();
        work = next;
        return advance(next, deadline, vbo);
    }

    private boolean advance(Work next, long deadline, boolean vbo) {
        var output = buffer;
        if (!next.ready) {
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < STEP_BATCH; i++) {
                    if (!surface.step(next.cells, next.material, vertex)) continue;
                    output.finishDrawing();
                    next.ready = true;
                    break;
                }
                if (next.ready) break;
            }
        }
        int bytes = output.getVertexCount() * STRIDE;
        if (!next.ready || System.nanoTime() >= deadline || uploads >= MAX_UPLOADS || bytes > MAX_UPLOAD_BYTES - uploadedBytes) return false;
        try {
            var created = bytes == 0 ? null : upload(output, next.mode, vbo);
            if (next.material == 0) {
                if (next.slice.wire != null) next.slice.wire.delete();
                next.slice.wire = created;
                next.slice.wireVersion = next.version;
            } else {
                next.part.delete();
                next.part.mesh = created;
                next.part.version = next.version;
            }
            if (bytes != 0) {
                uploads++;
                uploadedBytes += bytes;
            }
        } finally {
            output.reset();
            work = null;
        }
        return true;
    }

    void cancel() {
        if (work == null) return;
        if (!work.ready) buffer.finishDrawing();
        buffer.reset();
        work = null;
    }

    private Mesh upload(BufferBuilder output, int mode, boolean vbo) {
        if (vbo) {
            var vertices = new VertexBuffer(DefaultVertexFormats.POSITION);
            try {
                vertices.bufferData(output.getByteBuffer());
            } catch (RuntimeException | Error failure) {
                vertices.deleteGlBuffers();
                throw failure;
            }
            return new Mesh(vertices, 0, mode);
        }
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
        return new Mesh(null, display, mode);
    }

    void draw(Mesh mesh) {
        mesh.draw();
        draws++;
    }

    Metrics metrics() {
        return new Metrics(draws, uploads, uploadedBytes);
    }

    record Metrics(int draws, int uploads, int uploadedBytes) {
    }

    static final class Part {
        @Nullable Mesh mesh;
        int version = -1;
        double opacity;

        void delete() {
            if (mesh != null) mesh.delete();
            mesh = null;
        }
    }

    record Mesh(@Nullable VertexBuffer vertices, int displayList, int mode) {
        void draw() {
            if (vertices == null) {
                GlStateManager.callList(displayList);
            } else {
                vertices.bindBuffer();
                GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0);
                vertices.drawArrays(mode);
            }
        }

        void delete() {
            if (vertices == null) GLAllocation.deleteDisplayLists(displayList);
            else vertices.deleteGlBuffers();
        }
    }

    private static final class Work {
        final Slice slice;
        final ConstructionSurface.Cells cells;
        final int version;
        final byte material;
        final Part part;
        final int mode;
        boolean ready;

        Work(Slice slice, byte material, Part part) {
            this.slice = slice;
            cells = slice::cell;
            version = slice.version;
            this.material = material;
            this.part = part;
            mode = material == 0 ? GL11.GL_LINES : GL11.GL_QUADS;
        }
    }
}
