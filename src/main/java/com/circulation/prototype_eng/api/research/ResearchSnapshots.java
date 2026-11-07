package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.ints.AbstractInt2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.AbstractObject2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

/** BasicEntry snapshots also reject entry.setValue, unlike an unmodifiable set of live hash entries. */
final class ResearchSnapshots {
    private ResearchSnapshots() { }

    static Object2IntMap<ResourceLocation> levels(Object2IntMap<ResourceLocation> source) {
        return Object2IntMaps.unmodifiable(new FrozenLevels(source));
    }

    static <V> Int2ObjectMap<V> sparse(Int2ObjectMap<V> source) {
        return Int2ObjectMaps.unmodifiable(new FrozenSparse<>(source));
    }

    private static final class FrozenLevels extends AbstractObject2IntMap<ResourceLocation> {
        private final Object2IntOpenHashMap<ResourceLocation> values;
        private final ObjectSet<Object2IntMap.Entry<ResourceLocation>> entries;

        private FrozenLevels(Object2IntMap<ResourceLocation> source) {
            values = new Object2IntOpenHashMap<>(source);
            values.defaultReturnValue(0);
            var snapshot = new ObjectOpenHashSet<Object2IntMap.Entry<ResourceLocation>>();
            for (var entry : source.object2IntEntrySet()) { snapshot.add(new BasicEntry<>(entry.getKey(), entry.getIntValue())); }
            entries = ObjectSets.unmodifiable(snapshot);
        }

        @Override public ObjectSet<Object2IntMap.Entry<ResourceLocation>> object2IntEntrySet() { return entries; }
        @Override public int getInt(Object key) { return values.getInt(key); }
        @Override public boolean containsKey(Object key) { return values.containsKey(key); }
        @Override public int size() { return values.size(); }
    }

    private static final class FrozenSparse<V> extends AbstractInt2ObjectMap<V> {
        private final Int2ObjectOpenHashMap<V> values;
        private final ObjectSet<Int2ObjectMap.Entry<V>> entries;

        private FrozenSparse(Int2ObjectMap<V> source) {
            values = new Int2ObjectOpenHashMap<>(source);
            var snapshot = new ObjectOpenHashSet<Int2ObjectMap.Entry<V>>();
            for (var entry : source.int2ObjectEntrySet()) { snapshot.add(new BasicEntry<>(entry.getIntKey(), entry.getValue())); }
            entries = ObjectSets.unmodifiable(snapshot);
        }

        @Override public ObjectSet<Int2ObjectMap.Entry<V>> int2ObjectEntrySet() { return entries; }
        @Override public V get(int key) { return values.get(key); }
        @Override public boolean containsKey(int key) { return values.containsKey(key); }
        @Override public int size() { return values.size(); }
    }
}
