package com.circulation.prototype_eng.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.AbstractObject2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

/**
 * Position map storing only Minecraft's packed longs, never BlockPos keys.
 * Object operations snapshot coordinates at insertion; iteration is available only through long keys.
 * Object key/entry views and object forEach are intentionally unsupported.
 * Encoding follows BlockPos.toLong(), including its native coordinate masking.
 * Null keys are unsupported, null values and fastutil's configurable default return value are supported.
 * Like the underlying fastutil map, this class requires external synchronization when shared.
 * Published maps are read-only by contract; their owners and consumers must not mutate them.
 */
@SuppressWarnings("unused") // Public primitive operations also serve Java integrations.
public final class BlockPos2ObjectMap<V> extends AbstractObject2ObjectMap<BlockPos, V> {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final BlockPos2ObjectMap<?> EMPTY = new BlockPos2ObjectMap<>(Long2ObjectMaps.emptyMap());
    private static final int NUM_X_BITS = 1 + MathHelper.log2(MathHelper.smallestEncompassingPowerOfTwo(30000000));
    private static final int NUM_Z_BITS;
    private static final int NUM_Y_BITS;
    private static final int Y_SHIFT;
    private static final int X_SHIFT;
    private static final long X_MASK;
    private static final long Y_MASK;
    private static final long Z_MASK;

    static {
        NUM_Z_BITS = NUM_X_BITS;
        NUM_Y_BITS = 64 - NUM_X_BITS - NUM_Z_BITS;
        Y_SHIFT = NUM_Z_BITS;
        X_SHIFT = Y_SHIFT + NUM_Y_BITS;
        X_MASK = (1L << NUM_X_BITS) - 1L;
        Y_MASK = (1L << NUM_Y_BITS) - 1L;
        Z_MASK = (1L << NUM_Z_BITS) - 1L;
    }

    private final Long2ObjectMap<V> backing;

    public BlockPos2ObjectMap() {
        this(16);
    }

    public BlockPos2ObjectMap(int expected) {
        this(new Long2ObjectOpenHashMap<>(expected));
    }

    public BlockPos2ObjectMap(Map<? extends BlockPos, ? extends V> source) {
        this(source.size());
        putAll(source);
    }

    private BlockPos2ObjectMap(Long2ObjectMap<V> backing) {
        this.backing = backing;
    }

    public BlockPos2ObjectMap(BlockPos2ObjectMap<? extends V> source) {
        this(source.size());
        backing.putAll(source.backing);
    }

    @SuppressWarnings("unchecked") // The shared empty map has no values and cannot be mutated.
    public static <V> BlockPos2ObjectMap<V> emptyMap() {
        return (BlockPos2ObjectMap<V>) EMPTY;
    }

    public static long toLong(int x, int y, int z) {
        return ((long) x & X_MASK) << X_SHIFT | ((long) y & Y_MASK) << Y_SHIFT | ((long) z & Z_MASK);
    }

    public static int unpackX(long position) {
        return (int) (position << (64 - X_SHIFT - NUM_X_BITS) >> (64 - NUM_X_BITS));
    }

    public static int unpackY(long position) {
        return (int) (position << (64 - Y_SHIFT - NUM_Y_BITS) >> (64 - NUM_Y_BITS));
    }

    public static int unpackZ(long position) {
        return (int) (position << (64 - NUM_Z_BITS) >> (64 - NUM_Z_BITS));
    }

    @Override
    public void putAll(Map<? extends BlockPos, ? extends V> source) {
        for (var entry : source.entrySet()) put(entry.getKey(), entry.getValue());
    }

    public void putAll(BlockPos2ObjectMap<? extends V> source) {
        backing.putAll(source.backing);
    }

    public V get(long position) {
        return backing.get(position);
    }

    @Override
    public V get(Object position) {
        return position instanceof BlockPos pos ? get(pos.toLong()) : defaultReturnValue();
    }

    public boolean containsKey(long position) {
        return backing.containsKey(position);
    }

    @Override
    public boolean containsKey(Object position) {
        return position instanceof BlockPos pos && containsKey(pos.toLong());
    }

    public V getOrDefault(long position, V fallback) {
        return backing.getOrDefault(position, fallback);
    }

    @Override
    public V getOrDefault(Object position, V fallback) {
        return position instanceof BlockPos pos ? getOrDefault(pos.toLong(), fallback) : fallback;
    }

    public V put(long position, V value) {
        return backing.put(position, value);
    }

    @Override
    public V put(BlockPos position, V value) {
        return put(position.toLong(), value);
    }

    public V putIfAbsent(long position, V value) {
        return backing.putIfAbsent(position, value);
    }

    @Override
    public V putIfAbsent(BlockPos position, V value) {
        return putIfAbsent(position.toLong(), value);
    }

    public V remove(long position) {
        return backing.remove(position);
    }

    @Override
    public V remove(Object position) {
        return position instanceof BlockPos pos ? remove(pos.toLong()) : defaultReturnValue();
    }

    public boolean remove(long position, Object value) {
        return backing.remove(position, value);
    }

    @Override
    public boolean remove(Object position, Object value) {
        return position instanceof BlockPos pos && remove(pos.toLong(), value);
    }

    public V replace(long position, V value) {
        return backing.replace(position, value);
    }

    @Override
    public V replace(BlockPos position, V value) {
        return replace(position.toLong(), value);
    }

    public boolean replace(long position, V previous, V value) {
        return backing.replace(position, previous, value);
    }

    @Override
    public boolean replace(BlockPos position, V previous, V value) {
        return replace(position.toLong(), previous, value);
    }

    public V computeIfAbsent(long position, LongFunction<? extends V> factory) {
        return backing.computeIfAbsent(position, factory);
    }

    public V compute(long position, BiFunction<? super Long, ? super V, ? extends V> function) {
        return backing.compute(position, function);
    }

    public V computeIfPresent(long position, BiFunction<? super Long, ? super V, ? extends V> function) {
        return backing.computeIfPresent(position, function);
    }

    public V merge(long position, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        return backing.merge(position, value, function);
    }

    /**
     * Live primitive entries support iterator removal and setValue without decoding BlockPos objects.
     */
    public ObjectSet<Long2ObjectMap.Entry<V>> long2ObjectEntrySet() {
        return backing.long2ObjectEntrySet();
    }

    /**
     * Reuses the current entry; consume its key/value before advancing and do not retain the entry.
     */
    public ObjectIterator<Long2ObjectMap.Entry<V>> fastIterator() {
        return Long2ObjectMaps.fastIterator(backing);
    }

    @Override
    public @NotNull ObjectCollection<V> values() {
        return backing.values();
    }

    @Override
    public boolean containsValue(Object value) {
        return backing.containsValue(value);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public void defaultReturnValue(V value) {
        backing.defaultReturnValue(value);
    }

    @Override
    public V defaultReturnValue() {
        return backing.defaultReturnValue();
    }

    public LongSet longKeySet() {
        return backing.keySet();
    }

    @Override
    public ObjectSet<Entry<BlockPos, V>> object2ObjectEntrySet() {
        throw new UnsupportedOperationException("Use long2ObjectEntrySet()");
    }

    @Override
    @Deprecated
    public @NotNull ObjectSet<Map.Entry<BlockPos, V>> entrySet() {
        throw new UnsupportedOperationException("Use long2ObjectEntrySet()");
    }

    @Override
    public @NotNull ObjectSet<BlockPos> keySet() {
        throw new UnsupportedOperationException("Use longKeySet()");
    }

    @Override
    public void forEach(BiConsumer<? super BlockPos, ? super V> action) {
        throw new UnsupportedOperationException("Use long2ObjectEntrySet()");
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof Map<?, ?> map) || size() != map.size()) return false;
        if (other instanceof BlockPos2ObjectMap<?> packed) return backing.equals(packed.backing);
        for (var entries = fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            var key = BlockPos.fromLong(entry.getLongKey());
            if (!map.containsKey(key) || !Objects.equals(entry.getValue(), map.get(key))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (var entries = fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            long packed = entry.getLongKey();
            int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
            hash += (y + z * 31) * 31 + x ^ Objects.hashCode(entry.getValue());
        }
        return hash;
    }

    @Override
    public String toString() {
        return backing.toString();
    }
}
