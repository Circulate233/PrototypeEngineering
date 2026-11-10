package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.ObjLongConsumer;

/** Owned AEKey quantities whose entry views cannot mutate the captured values. */
public final class ImmutableResourceAmounts extends Object2LongMaps.UnmodifiableMap<AEKey> implements AEKey2LongMap {
    private static final ImmutableResourceAmounts EMPTY = new ImmutableResourceAmounts(new AEKey2LongMap.LinkdOpenHashMap());
    private final AEKey2LongMap owned;
    private final ObjectSet<Object2LongMap.Entry<AEKey>> immutableEntries;

    private ImmutableResourceAmounts(AEKey2LongMap owned) {
        super(owned);
        this.owned = owned;
        immutableEntries = ObjectSets.unmodifiable(new EntryView(owned));
    }

    public static ImmutableResourceAmounts empty() { return EMPTY; }

    /** Copies arbitrary external views; only this final owning type can safely be reused. */
    public static ImmutableResourceAmounts copyOf(AEKey2LongMap source) {
        Objects.requireNonNull(source);
        if (source instanceof ImmutableResourceAmounts immutable) return immutable;
        var builder = builder();
        source.forEachEntry(builder::addTo);
        return builder.build();
    }

    public static Builder builder() { return new Builder(); }

    @Override public long addTo(AEKey key, long increment) { throw new UnsupportedOperationException("Immutable resource quantities"); }

    @Override public ObjectSet<Object2LongMap.Entry<AEKey>> object2LongEntrySet() { return immutableEntries; }

    @Override
    @Deprecated
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"}) // Override the legacy boxed view too, so it cannot leak mutable entries.
    public @NotNull ObjectSet<Map.Entry<AEKey, Long>> entrySet() { return (ObjectSet) immutableEntries; }

    @Override public void forEachEntry(ObjLongConsumer<? super AEKey> action) { owned.forEachEntry(action); }

    /** Incremental owner-side copying; build transfers ownership in O(1) and closes this builder. */
    public static final class Builder {
        @Nullable private AEKey2LongMap values = new AEKey2LongMap.LinkdOpenHashMap();

        private Builder() { }

        public void addTo(AEKey key, long amount) {
            var target = Objects.requireNonNull(values, "Resource snapshot builder is closed");
            Objects.requireNonNull(key);
            long previous = target.getLong(key);
            if (amount > 0 && previous > Long.MAX_VALUE - amount || amount < 0 && previous < Long.MIN_VALUE - amount)
                throw new ArithmeticException("Resource snapshot amount overflow");
            target.addTo(key, amount);
        }

        public ImmutableResourceAmounts build() {
            var captured = Objects.requireNonNull(values, "Resource snapshot builder is closed");
            values = null;
            return captured.isEmpty() ? EMPTY : new ImmutableResourceAmounts(captured);
        }
    }

    @SuppressWarnings("CloneableClassInSecureContext") // A cloned view still exposes only immutable detached entries.
    private static final class EntryView extends AbstractObjectSet<Object2LongMap.Entry<AEKey>> {
        private final AEKey2LongMap values;

        private EntryView(AEKey2LongMap values) { this.values = values; }

        @Override public int size() { return values.size(); }

        @Override public boolean contains(Object value) { return values.object2LongEntrySet().contains(value); }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // The iterator exposes detached immutable entries, never the backing iterator.
        public @NotNull ObjectIterator<Object2LongMap.Entry<AEKey>> iterator() {
            var iterator = values.object2LongEntrySet().iterator();
            return new ObjectIterator<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Object2LongMap.Entry<AEKey> next() {
                    var entry = iterator.next();
                    // BasicEntry rejects both primitive and boxed setValue. Never expose the
                    // backing map entry, including through arrays, streams or fastIterator.
                    return new AbstractObject2LongMap.BasicEntry<>(entry.getKey(), entry.getLongValue());
                }
            };
        }
    }
}
