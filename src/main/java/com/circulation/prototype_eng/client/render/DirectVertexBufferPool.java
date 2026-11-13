package com.circulation.prototype_eng.client.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Packed CPU buffers retain their native byte order across borrow/seal/recycle.
 */
public class DirectVertexBufferPool implements AutoCloseable {
    private final Int2ObjectAVLTreeMap<ObjectArrayFIFOQueue<ByteBuffer>> free = new Int2ObjectAVLTreeMap<>();
    private final long maxRetainedBytes;
    private final int maxEntries;
    private long retainedBytes;
    private int count;
    private boolean closed;

    public DirectVertexBufferPool() {
        this(256L * 1024 * 1024, 0);
    }

    public DirectVertexBufferPool(long maxRetainedBytes) {
        this(maxRetainedBytes, 0);
    }

    public DirectVertexBufferPool(long maxRetainedBytes, int maxEntries) {
        if (maxRetainedBytes < 0) throw new IllegalArgumentException("Negative packed pool limit");
        if (maxEntries < 0) throw new IllegalArgumentException("Negative packed pool entry limit");
        this.maxRetainedBytes = maxRetainedBytes;
        this.maxEntries = maxEntries;
    }

    public synchronized ByteBuffer borrow(int minimumBytes) {
        if (minimumBytes < 0) throw new IllegalArgumentException("Negative packed allocation");
        var suitable = free.tailMap(minimumBytes);
        if (!suitable.isEmpty()) {
            int capacity = suitable.firstIntKey();
            var bucket = free.get(capacity);
            var best = bucket.dequeue();
            if (bucket.isEmpty()) free.remove(capacity);
            retainedBytes -= capacity;
            count--;
            best.clear();
            return best.order(ByteOrder.nativeOrder());
        }
        return ByteBuffer.allocateDirect(Math.max(32 * 1024, minimumBytes)).order(ByteOrder.nativeOrder());
    }

    public synchronized void release(ByteBuffer buffer) {
        buffer.clear();
        if (closed) return;
        int capacity = buffer.capacity();
        var bucket = free.get(capacity);
        if (bucket == null) {
            bucket = new ObjectArrayFIFOQueue<>();
            free.put(capacity, bucket);
        }
        bucket.enqueueFirst(buffer);
        retainedBytes += capacity;
        count++;
        while (maxRetainedBytes > 0 && retainedBytes > maxRetainedBytes || maxEntries > 0 && count > maxEntries) {
            int key = free.lastIntKey();
            var largest = free.get(key);
            largest.dequeueLast();
            retainedBytes -= key;
            count--;
            if (largest.isEmpty()) free.remove(key);
        }
    }

    public synchronized void clear() {
        free.clear();
        retainedBytes = 0;
        count = 0;
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
    }
}
