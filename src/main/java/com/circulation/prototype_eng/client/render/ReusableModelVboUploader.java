package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.ARBMapBufferRange;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native upload, client-array fallback and two-slot mapped writes; every native operation stays on client owner. */
public class ReusableModelVboUploader implements AutoCloseable {
    public static final int STRIDE = 28;
    private final ModelClientThread client;
    private final Map<Key, Entry> mapped = new Object2ObjectOpenHashMap<>();
    private final ArrayDeque<Upload> freeUploads = new ArrayDeque<>();
    private long freeUploadBytes;
    private @Nullable ByteBuffer mergeScratch;
    private boolean closed;
    public ReusableModelVboUploader(ModelClientThread client) {
        this.client = client;
    }

    private static void enableArrays() {
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
    }

    private static void disableArrays() {
        GlStateManager.resetColor();
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    public @Nullable Ticket tryMap(GeckoRenderRequest request, MachineRenderPass pass, int capacity) {
        client.requireOwner();
        if (closed || request.owner().retired() || capacity <= 0 || !supportsMapped()) return null;
        var key = new Key(request.baseKey(), pass);
        var entry = mapped.computeIfAbsent(key, ignored -> new Entry());
        Slot available = null;
        for (int i = 0; i < entry.slots.length; i++) {
            var slot = entry.slots[i];
            if (slot == null) {
                slot = new Slot(createBuffer());
                entry.slots[i] = slot;
            }
            if (!slot.writer && slot.readers == 0 && !slot.deleted) {
                available = slot;
                break;
            }
        }
        // Both slots can still be read by old current/queued draw/Bloom results. The caller uses packed fallback.
        if (available == null) return null;
        available.writer = true;
        ByteBuffer bytes;
        try {
            bytes = mapBuffer(available.id, capacity);
        } catch (RuntimeException | Error failure) {
            available.writer = false;
            if (entry.retired || closed) try {
                deleteIfUnused(available);
            } catch (RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
            throw failure;
        }
        if (bytes == null) {
            available.writer = false;
            if (entry.retired || closed) deleteIfUnused(available);
            return null;
        }
        try {
            bytes.clear();
            bytes.limit(capacity);
            bytes.order(ByteOrder.nativeOrder());
            return new Ticket(key, entry, available, bytes, capacity);
        } catch (RuntimeException | Error failure) {
            try {
                unmapBuffer(available.id);
            } catch (RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            } finally {
                available.writer = false;
            }
            entry.retired = true;
            mapped.remove(key, entry);
            for (var slot : entry.slots)
                if (slot != null) try {
                    deleteIfUnused(slot);
                } catch (RuntimeException | Error exception) {
                    failure.addSuppressed(exception);
                }
            throw failure;
        }
    }

    public @Nullable GpuDraw finish(Ticket ticket, int bytesWritten, boolean publish) {
        client.requireOwner();
        if (ticket.finished) throw new IllegalStateException("Mapped ticket already finished");
        ticket.finished = true;
        var slot = ticket.slot;
        boolean validLength = bytesWritten >= 0 && bytesWritten <= ticket.capacity && bytesWritten % STRIDE == 0;
        boolean unmapped = false;
        Throwable failure = null;
        try {
            unmapped = unmapBuffer(slot.id);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        } finally {
            slot.writer = false;
        }
        boolean current = !closed && !ticket.entry.retired && mapped.get(ticket.key) == ticket.entry &&
            !slot.deleted && unmapped && validLength && publish && bytesWritten > 0 && failure == null;
        if (current) {
            slot.readers++;
            return new GpuDraw(this, slot.id, bytesWritten / STRIDE, () -> release(ticket.entry, slot));
        }
        if (failure == null && !unmapped && publish && bytesWritten > 0 && !closed && !ticket.entry.retired && mapped.get(ticket.key) == ticket.entry)
            failure = new IllegalStateException("Native model buffer unmap failed; no complete result may publish");
        if (!unmapped || failure != null) ticket.entry.retired = true;
        if (ticket.entry.retired || closed) {
            mapped.remove(ticket.key, ticket.entry);
            try {
                deleteIfUnused(slot);
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
            for (var other : ticket.entry.slots)
                if (other != null && other != slot) try {
                    deleteIfUnused(other);
                } catch (RuntimeException | Error exception) {
                    failure = BuiltModelBuffers.append(failure, exception);
                }
        }
        BuiltModelBuffers.rethrow(failure);
        if (!validLength) throw new IllegalArgumentException("Mapped output exceeded ticket capacity or vertex stride");
        return null;
    }

    public void retire(GeckoRenderRequest.Owner owner) {
        client.requireOwner();
        Throwable failure = null;
        for (var item : Map.copyOf(mapped).entrySet())
            if (item.getKey().base.owner() == owner) {
                mapped.remove(item.getKey(), item.getValue());
                item.getValue().retired = true;
                for (var slot : item.getValue().slots)
                    if (slot != null) try {
                        deleteIfUnused(slot);
                    } catch (RuntimeException | Error exception) {
                        failure = BuiltModelBuffers.append(failure, exception);
                    }
            }
        BuiltModelBuffers.rethrow(failure);
    }

    public void retire(GeckoRenderRequest.BaseKey base) {
        client.requireOwner();
        Throwable failure = null;
        for (var item : Map.copyOf(mapped).entrySet())
            if (item.getKey().base.equals(base)) {
                mapped.remove(item.getKey(), item.getValue());
                item.getValue().retired = true;
                for (var slot : item.getValue().slots)
                    if (slot != null) try {
                        deleteIfUnused(slot);
                    } catch (RuntimeException | Error exception) {
                        failure = BuiltModelBuffers.append(failure, exception);
                    }
            }
        BuiltModelBuffers.rethrow(failure);
    }

    private void release(Entry entry, Slot slot) {
        client.requireOwner();
        slot.readers--;
        if (entry.retired || closed) deleteIfUnused(slot);
    }

    private void deleteIfUnused(Slot slot) {
        if (slot.writer || slot.readers != 0 || slot.deleted) return;
        slot.deleted = true;
        deleteBuffer(slot.id);
    }

    public void draw(BuiltModelBuffers.Lease lease, MachineRenderPass pass) {
        client.requireOwner();
        var result = lease.buffers();
        if (closed || !result.bindTexture()) return;
        var resident = result.gpu().get(pass);
        if (resident != null) {
            resident.draw();
            return;
        }
        ByteBuffer bytes;
        int count;
        var builder = result.builders().get(pass);
        if (builder != null) {
            bytes = builder.getByteBuffer().duplicate().order(ByteOrder.nativeOrder());
            count = builder.getVertexCount();
        } else {
            var packed = result.packed().get(pass);
            if (packed == null) return;
            bytes = packed.duplicate().order(ByteOrder.nativeOrder());
            count = bytes.remaining() / STRIDE;
        }
        if (count == 0) return;
        if (!useVbo()) {
            drawClient(bytes, count);
            return;
        }
        var uploaded = result.uploaded(pass);
        if (uploaded == null) {
            int id = borrowUpload();
            int uploadedBytes = bytes.remaining();
            try {
                uploadBuffer(id, bytes);
            } catch (RuntimeException | Error failure) {
                try {
                    deleteBuffer(id);
                } catch (RuntimeException | Error exception) {
                    failure.addSuppressed(exception);
                }
                throw failure;
            }
            uploaded = new GpuDraw(this, id, count, () -> recycleUpload(id, uploadedBytes));
            result.uploaded(pass, uploaded);
        }
        uploaded.draw();
    }

    /** Original synchronous multi-result path. Cached/GPU results retain individual VBOs; uncached CPU data merges in bounded chunks. */
    public void drawMultiple(List<BuiltModelBuffers.Lease> leases, MachineRenderPass pass) {
        client.requireOwner();
        if (closed || leases.isEmpty()) return;
        var first = leases.getFirst().buffers();
        int cached = 0;
        for (var lease : leases) {
            var value = lease.buffers();
            if (!first.origin().equals(value.origin()) || first.textureIdentity() != value.textureIdentity()) {
                leases.forEach(item -> draw(item, pass));
                return;
            }
            if (value.gpu().containsKey(pass) || value.hasUploaded(pass)) cached++;
        }
        if (leases.size() == 1 || cached * 2 >= leases.size()) {
            leases.forEach(item -> draw(item, pass));
            return;
        }
        if (!first.bindTexture()) return;
        if (mergeScratch == null)
            mergeScratch = ByteBuffer.allocateDirect((8 * 1024 * 1024 / STRIDE) * STRIDE).order(ByteOrder.nativeOrder());
        mergeScratch.clear();
        for (var lease : leases) {
            var value = lease.buffers();
            var resident = value.gpu().get(pass);
            if (resident != null) {
                if (mergeScratch.position() > 0) flushMerged();
                resident.draw();
                continue;
            }
            ByteBuffer source;
            var buffer = value.builders().get(pass);
            if (buffer != null) source = buffer.getByteBuffer().duplicate();
            else {
                var packed = value.packed().get(pass);
                if (packed == null) continue;
                source = packed.duplicate();
            }
            while (source.hasRemaining()) {
                int amount = Math.min(source.remaining(), mergeScratch.remaining());
                var piece = source.duplicate();
                piece.limit(piece.position() + amount);
                mergeScratch.put(piece);
                source.position(source.position() + amount);
                if (!mergeScratch.hasRemaining()) flushMerged();
            }
        }
        if (mergeScratch.position() > 0) flushMerged();
    }

    private void flushMerged() {
        var bytes = Objects.requireNonNull(mergeScratch);
        bytes.flip();
        int vertices = bytes.remaining() / STRIDE;
        if (!useVbo()) drawClient(bytes.duplicate(), vertices);
        else {
            int id = borrowUpload();
            int uploadedBytes = bytes.remaining();
            try {
                uploadBuffer(id, bytes.duplicate());
                drawBuffer(id, vertices);
            } finally {
                recycleUpload(id, uploadedBytes);
            }
        }
        bytes.clear();
    }

    private int borrowUpload() {
        if (freeUploads.isEmpty()) return createBuffer();
        var free = freeUploads.pop();
        freeUploadBytes -= free.bytes;
        return free.id;
    }

    private void recycleUpload(int id, int bytes) {
        if (closed || bytes > 128L * 1024 * 1024) {
            deleteBuffer(id);
            return;
        }
        while (!freeUploads.isEmpty() && (freeUploads.size() >= 256 || freeUploadBytes + bytes > 128L * 1024 * 1024)) {
            var old = freeUploads.removeLast();
            freeUploadBytes -= old.bytes;
            deleteBuffer(old.id);
        }
        freeUploads.push(new Upload(id, bytes));
        freeUploadBytes += bytes;
    }

    @Override
    public void close() {
        client.requireOwner();
        if (closed) return;
        closed = true;
        mergeScratch = null;
        Throwable failure = null;
        var entries = Map.copyOf(mapped);
        mapped.clear();
        for (var entry : entries.values()) {
            entry.retired = true;
            for (var slot : entry.slots)
                if (slot != null) try {
                    deleteIfUnused(slot);
                } catch (RuntimeException | Error exception) {
                    failure = BuiltModelBuffers.append(failure, exception);
                }
        }
        while (!freeUploads.isEmpty()) try {
            deleteBuffer(freeUploads.pop().id);
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        freeUploadBytes = 0;
        BuiltModelBuffers.rethrow(failure);
    }

    protected boolean useVbo() {
        return OpenGlHelper.useVbo();
    }

    protected boolean supportsMapped() {
        var caps = GLContext.getCapabilities();
        return useVbo() && (caps.OpenGL30 || caps.GL_ARB_map_buffer_range);
    }

    protected int createBuffer() {
        return OpenGlHelper.glGenBuffers();
    }

    protected void deleteBuffer(int id) {
        OpenGlHelper.glDeleteBuffers(id);
    }

    protected @Nullable ByteBuffer mapBuffer(int id, int capacity) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, id);
        try {
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, capacity, GL15.GL_STREAM_DRAW);
            var caps = GLContext.getCapabilities();
            int access = GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT;
            return caps.OpenGL30 ? GL30.glMapBufferRange(OpenGlHelper.GL_ARRAY_BUFFER, 0, capacity, access, null) :
                ARBMapBufferRange.glMapBufferRange(OpenGlHelper.GL_ARRAY_BUFFER, 0, capacity, access, null);
        } finally {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, previous);
        }
    }

    protected boolean unmapBuffer(int id) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, id);
        try {
            return GL15.glUnmapBuffer(OpenGlHelper.GL_ARRAY_BUFFER);
        } finally {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, previous);
        }
    }

    protected void uploadBuffer(int id, ByteBuffer bytes) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, id);
        try {
            OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, bytes, OpenGlHelper.GL_STATIC_DRAW);
        } finally {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, previous);
        }
    }

    protected void drawBuffer(int id, int vertices) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        Throwable failure = null;
        boolean pushed = false;
        try {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, id);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            pushed = true;
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            enableArrays();
            GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, 20L);
            GL11.glNormalPointer(GL11.GL_BYTE, STRIDE, 24L);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, vertices);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        if (pushed) {
            try {
                disableArrays();
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
            try {
                GL11.glPopClientAttrib();
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
        }
        try {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, previous);
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    protected void drawClient(ByteBuffer bytes, int vertices) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        Throwable failure = null;
        boolean pushed = false;
        try {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            pushed = true;
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            enableArrays();
            bytes.position(0);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, bytes);
            bytes.position(12);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, bytes);
            bytes.position(20);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, bytes);
            bytes.position(24);
            GL11.glNormalPointer(GL11.GL_BYTE, STRIDE, bytes);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, vertices);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        if (pushed) {
            try {
                disableArrays();
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
            try {
                GL11.glPopClientAttrib();
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
        }
        try {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, previous);
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    private record Upload(int id, int bytes) {
    }

    private record Key(GeckoRenderRequest.BaseKey base, MachineRenderPass pass) {
    }

    private static final class Entry {
        final Slot[] slots = new Slot[2];
        boolean retired;
    }

    private static final class Slot {
        final int id;
        boolean writer;
        int readers;
        boolean deleted;

        Slot(int id) {
            this.id = id;
        }
    }

    public static final class Ticket {
        private final Key key;
        private final Entry entry;
        private final Slot slot;
        private final ByteBuffer bytes;
        private final int capacity;
        private volatile boolean finished;

        private Ticket(Key key, Entry entry, Slot slot, ByteBuffer bytes, int capacity) {
            this.key = key;
            this.entry = entry;
            this.slot = slot;
            this.bytes = bytes;
            this.capacity = capacity;
        }

        /** Only the task's worker writes this view until it dispatches exactly one finish/abort. */
        public IntBuffer writerView() {
            return bytes.asIntBuffer();
        }

        public boolean finished() {
            return finished;
        }
    }

    public static final class GpuDraw implements AutoCloseable {
        private final ReusableModelVboUploader owner;
        private final int id;
        private final int vertices;
        private final Runnable release;
        private boolean released;

        private GpuDraw(ReusableModelVboUploader owner, int id, int vertices, Runnable release) {
            this.owner = owner;
            this.id = id;
            this.vertices = vertices;
            this.release = release;
        }

        public void draw() {
            owner.client.requireOwner();
            if (released) throw new IllegalStateException("GPU slot already released");
            owner.drawBuffer(id, vertices);
        }

        @Override
        public void close() {
            owner.client.execute(() -> {
                if (released) return;
                released = true;
                release.run();
            });
        }
    }
}
