package com.circulation.prototype_eng.client.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import net.minecraft.client.renderer.BufferBuilder;

import java.util.ArrayDeque;

/** Buffers enter this pool only after their owned result's final reader exits. */
public class ModelBufferPool implements AutoCloseable {
    private final Int2ObjectAVLTreeMap<ArrayDeque<Entry>> free = new Int2ObjectAVLTreeMap<>();
    private final ArrayDeque<Entry> oversize = new ArrayDeque<>();
    private final Limits limits;
    private long pooledBytes;
    private long oversizeBytes;
    private int count;
    private int operations;
    private boolean closed;

    public ModelBufferPool() {
        this(Limits.defaults());
    }

    public ModelBufferPool(Limits limits) {
        this.limits = limits;
    }

    public synchronized BufferBuilder borrow(int minimumBytes) {
        if (minimumBytes < 0) throw new IllegalArgumentException("Negative builder allocation");
        if ((++operations & 63) == 0) maintain(System.nanoTime());
        var suitable = free.tailMap(minimumBytes);
        if (!suitable.isEmpty()) {
            int capacity = suitable.firstIntKey();
            var bucket = free.get(capacity);
            var entry = bucket.removeFirst();
            if (bucket.isEmpty()) free.remove(capacity);
            pooledBytes -= capacity;
            count--;
            return entry.buffer;
        }
        Entry best = null;
        for (var entry : oversize)
            if (entry.capacity >= minimumBytes && (best == null || entry.capacity < best.capacity)) best = entry;
        if (best != null) {
            oversize.remove(best);
            oversizeBytes -= best.capacity;
            count--;
            return best.buffer;
        }
        // Vanilla constructor counts ints, whereas pool capacities and task estimates are bytes.
        return new BufferBuilder(Math.toIntExact((Math.max(32 * 1024, minimumBytes) + 3L) / 4));
    }

    public synchronized void release(BufferBuilder buffer) {
        buffer.reset();
        buffer.getByteBuffer().clear();
        if (closed) return;
        int capacity = buffer.getByteBuffer().capacity();
        if (limits.hardMaxBytes > 0 && capacity > limits.hardMaxBytes) return;
        long now = System.nanoTime();
        var entry = new Entry(buffer, capacity, now);
        if (limits.normalMaxBytes > 0 && capacity > limits.normalMaxBytes) {
            if (limits.oversizeMaxBytes > 0 && capacity > limits.oversizeMaxBytes) return;
            while (!oversize.isEmpty() && (limits.oversizeEntries > 0 && oversize.size() >= limits.oversizeEntries ||
                limits.oversizeMaxBytes > 0 && capacity > limits.oversizeMaxBytes - oversizeBytes)) evictOversize();
            oversize.addLast(entry);
            oversizeBytes += capacity;
        } else {
            var bucket = free.get(capacity);
            if (bucket == null) {
                bucket = new ArrayDeque<>();
                free.put(capacity, bucket);
            }
            bucket.addFirst(entry);
            pooledBytes += capacity;
        }
        count++;
        if ((++operations & 63) == 0 || limits.totalMaxBytes > 0 && pooledBytes + oversizeBytes > limits.totalMaxBytes ||
            limits.maxEntries > 0 && count > limits.maxEntries) maintain(now);
    }

    private void evictOversize() {
        var old = oversize.removeFirst();
        oversizeBytes -= old.capacity;
        count--;
    }

    private void maintain(long now) {
        while (!oversize.isEmpty() && limits.oversizeTtlNanos > 0 && now - oversize.getFirst().returnedAt >= limits.oversizeTtlNanos)
            evictOversize();
        while (limits.totalMaxBytes > 0 && pooledBytes + oversizeBytes > limits.totalMaxBytes || limits.maxEntries > 0 && count > limits.maxEntries) {
            if (!oversize.isEmpty()) {
                evictOversize();
                continue;
            }
            if (free.isEmpty()) break;
            int key = free.lastIntKey();
            var bucket = free.get(key);
            bucket.removeLast();
            pooledBytes -= key;
            count--;
            if (bucket.isEmpty()) free.remove(key);
        }
    }

    public synchronized void clear() {
        free.clear();
        oversize.clear();
        pooledBytes = 0;
        oversizeBytes = 0;
        count = 0;
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
    }

    private record Entry(BufferBuilder buffer, int capacity, long returnedAt) {
    }

    public record Limits(int normalMaxBytes, int hardMaxBytes, int oversizeEntries, long oversizeMaxBytes,
                         long oversizeTtlNanos, long totalMaxBytes, int maxEntries) {
        public Limits {
            if (normalMaxBytes < 0 || hardMaxBytes < 0 || oversizeEntries < 0 || oversizeMaxBytes < 0 ||
                oversizeTtlNanos < 0 || totalMaxBytes < 0 || maxEntries < 0)
                throw new IllegalArgumentException("Negative builder pool limit");
        }

        public static Limits defaults() {
            return new Limits(64 * 1024 * 1024, 256 * 1024 * 1024, 4,
                512L * 1024 * 1024, 300_000_000_000L, 768L * 1024 * 1024, 0);
        }
    }
}
