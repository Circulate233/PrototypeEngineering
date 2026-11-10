package com.circulation.prototype_eng.api.research.reward;

import it.unimi.dsi.fastutil.objects.AbstractObject2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;

/**
 * Ordered immutable entries protect both map methods and entry.setValue.
 */
final class RewardSnapshots {
    private RewardSnapshots() {
    }

    static <K, V> Object2ObjectMap<K, V> map(Object2ObjectMap<K, V> source) {
        return Object2ObjectMaps.unmodifiable(new FrozenMap<>(source));
    }

    private static final class FrozenMap<K, V> extends AbstractObject2ObjectMap<K, V> {
        private final Object2ObjectLinkedOpenHashMap<K, V> values;
        private final ObjectSet<Object2ObjectMap.Entry<K, V>> entries;

        private FrozenMap(Object2ObjectMap<K, V> source) {
            values = new Object2ObjectLinkedOpenHashMap<>(source);
            var snapshot = new ObjectLinkedOpenHashSet<Object2ObjectMap.Entry<K, V>>();
            for (var entry : source.object2ObjectEntrySet())
                snapshot.add(new BasicEntry<>(entry.getKey(), entry.getValue()));
            entries = ObjectSets.unmodifiable(snapshot);
        }

        @Override
        public ObjectSet<Object2ObjectMap.Entry<K, V>> object2ObjectEntrySet() {
            return entries;
        }

        @Override
        public V get(Object key) {
            return values.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public int size() {
            return values.size();
        }
    }
}
