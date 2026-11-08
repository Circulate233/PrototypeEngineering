package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.BufferBuilder;

/**
 * Only D1's owner-thread builders: reuse direct buffers without importing PM's renderer-wide async pool.
 */
final class PreviewBlockBufferPool implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final ObjectArrayList<BufferBuilder> retained = new ObjectArrayList<>();
    private boolean closed;

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Preview buffers require their client owner thread");
    }

    BufferBuilder borrow() {
        requireOwner();
        if (closed) throw new IllegalStateException("Preview buffers are closed");
        return retained.isEmpty() ? new BufferBuilder((1 << 19) / Integer.BYTES) : retained.removeLast();
    }

    void recycle(BufferBuilder buffer) {
        requireOwner();
        buffer.setTranslation(0, 0, 0);
        buffer.reset();
        buffer.getByteBuffer().clear();
        // Bound retained direct memory; very large model buffers are dropped rather than pinned in the pool.
        if (!closed && retained.size() < 4 && buffer.getByteBuffer().capacity() <= 2 * 1024 * 1024)
            retained.add(buffer);
    }

    @Override
    public void close() {
        requireOwner();
        closed = true;
        retained.clear();
    }
}
