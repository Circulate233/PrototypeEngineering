package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.Slice;
import it.unimi.dsi.fastutil.ints.Int2IntAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBVertexBufferObject;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.util.Objects;

final class ConstructionMeshes {
    private static final int MAX_UPLOADS = 12;
    private static final int MAX_UPLOAD_BYTES = 1 << 20;
    private static final int STEP_BATCH = 16;
    static final int STRIDE = 3 * Float.BYTES;
    static final int PAGE_BYTES = 1 << 19;
    private static final int MAX_SECTION_VERTICES = 6 * ConstructionAnimation.SLICE_CELLS * 4;
    private final ConstructionSurface surface = new ConstructionSurface();
    private final ByteBuffer buffer = BufferUtils.createByteBuffer(MAX_SECTION_VERTICES * STRIDE);
    private @Nullable Work work;
    private int uploads;
    private int uploadedBytes;

    void beginFrame() {
        uploads = 0;
        uploadedBytes = 0;
    }

    boolean prefetching() {
        return work != null && work.opacity() <= ConstructionAnimation.MIN_OPACITY;
    }

    boolean resume(int frame, long deadline, boolean vbo, boolean allowPrefetch) {
        var current = work;
        if (current == null) return true;
        var slice = current.slice;
        boolean needed = current.opacity() > ConstructionAnimation.MIN_OPACITY || allowPrefetch
            && (current.material == 0 ? slice.prepareWire : current.material == ConstructionSurface.SOLID && slice.prepareShell);
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
        buffer.clear();
        surface.reset();
        work = next;
        return advance(next, deadline, vbo);
    }

    private boolean advance(Work next, long deadline, boolean vbo) {
        var output = buffer;
        if (!next.ready) {
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < STEP_BATCH; i++) {
                    if (!surface.step(next.cells, next.groupMaterial, next.vertex)) continue;
                    output.flip();
                    next.ready = true;
                    break;
                }
                if (next.ready) break;
            }
        }
        if (!next.ready || System.nanoTime() >= deadline || uploads >= MAX_UPLOADS) return false;
        int bytes = output.remaining();
        if (bytes > MAX_UPLOAD_BYTES - uploadedBytes) return false;
        try {
            var created = bytes == 0 ? null : next.slice.task.arena.store(output, vbo, next.material != 0);
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
            work = null;
        }
        return true;
    }

    void cancel() {
        work = null;
    }

    int uploads() {
        return uploads;
    }

    int uploadedBytes() {
        return uploadedBytes;
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

    record Mesh(Page page, int first, int count, int @Nullable [] vertices) {
        void delete() {
            page.release(first, count);
        }
    }

    static final class Arena {
        final int x, y, z;
        final ObjectArrayList<Page> pages = new ObjectArrayList<>();
        int drawPasses;
        float wireOpacity;
        float fadeOpacity;
        boolean mixedFade;

        Arena(BlockPos origin) {
            x = origin.getX();
            y = origin.getY();
            z = origin.getZ();
        }

        Mesh store(ByteBuffer source, boolean vbo, boolean shell) {
            int count = source.remaining() / STRIDE;
            for (int i = 0; i < pages.size(); i++) {
                var page = pages.get(i);
                int offset = page.reserve(count);
                if (offset >= 0) return page.store(offset, count, source, shell);
            }
            var page = new Page(this, vbo);
            pages.add(page);
            return page.store(page.reserve(count), count, source, shell);
        }
    }

    static final class Page {
        private static final int CAPACITY = PAGE_BYTES / STRIDE;
        final @Nullable ByteBuffer cpu;
        final int id;
        final ObjectArrayList<Mesh> solid = new ObjectArrayList<>();
        final ObjectArrayList<Part> fading = new ObjectArrayList<>();
        final ObjectArrayList<Mesh> wires = new ObjectArrayList<>();
        private final Arena arena;
        private final boolean coreVbo;
        private final Int2IntAVLTreeMap free = new Int2IntAVLTreeMap();
        private int live;

        Page(Arena arena, boolean vbo) {
            this.arena = arena;
            cpu = vbo ? null : BufferUtils.createByteBuffer(CAPACITY * STRIDE);
            coreVbo = vbo && GLContext.getCapabilities().OpenGL15;
            id = vbo ? OpenGlHelper.glGenBuffers() : 0;
            if (id != 0) {
                try {
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, id);
                    if (coreVbo)
                        GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, (long) CAPACITY * STRIDE, OpenGlHelper.GL_STATIC_DRAW);
                    else
                        ARBVertexBufferObject.glBufferDataARB(OpenGlHelper.GL_ARRAY_BUFFER, (long) CAPACITY * STRIDE, OpenGlHelper.GL_STATIC_DRAW);
                } catch (RuntimeException | Error failure) {
                    OpenGlHelper.glDeleteBuffers(id);
                    throw failure;
                }
            }
            free.put(0, CAPACITY);
        }

        int reserve(int count) {
            for (var iterator = free.int2IntEntrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                int offset = entry.getIntKey(), available = entry.getIntValue();
                if (available < count) continue;
                iterator.remove();
                if (available > count) free.put(offset + count, available - count);
                live++;
                return offset;
            }
            return -1;
        }

        Mesh store(int offset, int count, ByteBuffer source, boolean shell) {
            int byteOffset = offset * STRIDE;
            try {
                int[] vertices = null;
                if (id != 0) {
                    if (shell) {
                        vertices = new int[count * 3];
                        source.asIntBuffer().get(vertices);
                    }
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, id);
                    if (coreVbo) GL15.glBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, byteOffset, source);
                    else ARBVertexBufferObject.glBufferSubDataARB(OpenGlHelper.GL_ARRAY_BUFFER, byteOffset, source);
                } else {
                    Objects.requireNonNull(cpu).put(byteOffset, source, 0, source.remaining());
                }
                return new Mesh(this, offset, count, vertices);
            } catch (RuntimeException | Error failure) {
                release(offset, count);
                throw failure;
            }
        }

        void release(int offset, int count) {
            if (--live == 0) {
                if (id != 0) OpenGlHelper.glDeleteBuffers(id);
                arena.pages.remove(this);
                return;
            }
            int end = offset + count;
            var before = free.headMap(offset);
            if (!before.isEmpty()) {
                int previous = before.lastIntKey();
                if (previous + free.get(previous) == offset) {
                    offset = previous;
                    free.remove(previous);
                }
            }
            free.put(offset, end - offset + free.remove(end));
        }
    }

    private final class Work {
        final Slice slice;
        final ConstructionSurface.Cells cells;
        final ConstructionSurface.Vertex vertex = this::vertex;
        final int version;
        final byte material;
        final byte groupMaterial;
        final Part part;
        final int x, y, z;
        boolean ready;

        Work(Slice slice, byte material, Part part) {
            this.slice = slice;
            cells = slice::cell;
            version = slice.version;
            this.material = material;
            groupMaterial = material == 0 ? 0 : ConstructionSurface.groupMaterial(slice.group, material);
            this.part = part;
            var arena = slice.task.arena;
            x = (int) slice.view.minX - arena.x;
            y = (int) slice.view.minY - arena.y;
            z = (int) slice.view.minZ - arena.z;
        }

        double opacity() {
            return material == 0 ? slice.wireOpacity : part.opacity;
        }

        private void vertex(int x, int y, int z) {
            buffer.putFloat(this.x + x).putFloat(this.y + y).putFloat(this.z + z);
        }
    }
}
