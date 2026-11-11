package com.circulation.prototype_eng.api.network.resource;

import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.io.Serial;
import java.util.Objects;

/**
 * Immutable exact integer adjustment of a team's complete per-tick computing resource vector.
 */
public final class NetworkResourceModifier {
    public static final NetworkResourceModifier EMPTY = new Builder().build();
    private static final long ONE_MILLION = 1_000_000L;

    private final Object2LongMap<NetworkResourceType> flat;
    private final Object2LongMap<NetworkResourceType> multiplierPpm;

    private NetworkResourceModifier(Builder builder) {
        var metadata = new Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType>();
        this.flat = freeze(copy(builder.flat, false, metadata));
        this.multiplierPpm = freeze(copy(builder.multiplierPpm, false, metadata));
        for (long ppm : multiplierPpm.values()) {
            if (Math.addExact(ONE_MILLION, ppm) < 0L) {
                throw new IllegalArgumentException("Computing resource multiplier factor must be non-negative");
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Captures non-negative quantities and rejects conflicting metadata for a shared resource id.
     * Only this library's already validated immutable vector can be reused without another copy.
     */
    public static Object2LongMap<NetworkResourceType> copyResources(Object2LongMap<NetworkResourceType> resources) {
        if (resources instanceof CanonicalResources canonical && canonical.nonNegative) return canonical;
        return freeze(copy(resources, true, new Object2ObjectOpenHashMap<>()));
    }

    private static Object2LongOpenHashMap<NetworkResourceType> copy(
        Object2LongMap<NetworkResourceType> source, boolean nonNegative,
        Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType> metadata) {
        Objects.requireNonNull(source, "Computing resource vector");
        var copy = new Object2LongOpenHashMap<NetworkResourceType>();
        copy.defaultReturnValue(0L);
        for (var entry : source.object2LongEntrySet()) {
            requireConsistentType(entry.getKey(), metadata);
            long amount = entry.getLongValue();
            if (nonNegative && amount < 0L) {
                throw new IllegalArgumentException("Computing resource quantity must be non-negative");
            }
            copy.put(entry.getKey(), amount);
        }
        return copy;
    }

    private static void requireConsistentType(NetworkResourceType type,
                                              Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType> metadata) {
        Objects.requireNonNull(type, "Computing resource type");
        var retained = metadata.putIfAbsent(type.id(), type);
        if (retained != null && !retained.equals(type)) {
            throw new IllegalArgumentException("Conflicting computing resource metadata for " + type.id());
        }
    }

    private static Object2LongMap<NetworkResourceType> freeze(Object2LongOpenHashMap<NetworkResourceType> quantities) {
        return new CanonicalResources(new FrozenResources(quantities));
    }

    public Object2LongMap<NetworkResourceType> flatAdjustments() {
        return flat;
    }

    public Object2LongMap<NetworkResourceType> multiplierPpmAdjustments() {
        return multiplierPpm;
    }

    /**
     * Applies floor((base + flat) * (1_000_000 + ppm) / 1_000_000), rejecting intermediate overflow.
     */
    public Object2LongMap<NetworkResourceType> apply(Object2LongMap<NetworkResourceType> base) {
        var metadata = new Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType>();
        Object2LongMap<NetworkResourceType> quantities;
        if (base instanceof CanonicalResources canonical && canonical.nonNegative) {
            quantities = canonical;
            for (var type : canonical.keySet()) requireConsistentType(type, metadata);
        } else quantities = copy(base, true, metadata);
        for (var type : flat.keySet()) {
            requireConsistentType(type, metadata);
        }
        for (var type : multiplierPpm.keySet()) {
            requireConsistentType(type, metadata);
        }
        var result = new Object2LongOpenHashMap<NetworkResourceType>();
        result.defaultReturnValue(0L);
        for (var type : metadata.values()) {
            long adjustedBase = Math.addExact(quantities.getLong(type), flat.getLong(type));
            long factor = Math.addExact(ONE_MILLION, multiplierPpm.getLong(type));
            long numerator = Math.multiplyExact(adjustedBase, factor);
            if (adjustedBase < 0L || factor < 0L || numerator < 0L) {
                throw new IllegalArgumentException("Computing resource modifier produces a negative value for " + type.id());
            }
            result.put(type, Math.floorDiv(numerator, ONE_MILLION));
        }
        return freeze(result);
    }

    /**
     * Frozen entries close the setValue mutation route left open by fastutil's map/set wrappers.
     */
    private static final class CanonicalResources extends Object2LongMaps.UnmodifiableMap<NetworkResourceType> {
        @Serial
        private static final long serialVersionUID = 1L;
        private final boolean nonNegative;

        private CanonicalResources(FrozenResources quantities) {
            super(quantities);
            nonNegative = quantities.nonNegative;
        }
    }

    private static final class FrozenResources extends AbstractObject2LongMap<NetworkResourceType> {
        @Serial
        private static final long serialVersionUID = 1L;
        private final Object2LongOpenHashMap<NetworkResourceType> quantities;
        private final ObjectSet<Object2LongMap.Entry<NetworkResourceType>> entries;
        private final boolean nonNegative;

        private FrozenResources(Object2LongOpenHashMap<NetworkResourceType> quantities) {
            this.quantities = quantities;
            var entries = new ObjectOpenHashSet<Object2LongMap.Entry<NetworkResourceType>>();
            boolean nonNegative = true;
            for (var entry : quantities.object2LongEntrySet()) {
                entries.add(new BasicEntry<>(entry.getKey(), entry.getLongValue()));
                nonNegative &= entry.getLongValue() >= 0;
            }
            this.entries = ObjectSets.unmodifiable(entries);
            this.nonNegative = nonNegative;
        }

        @Override
        public long getLong(Object key) {
            return quantities.getLong(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return quantities.containsKey(key);
        }

        @Override
        public int size() {
            return quantities.size();
        }

        @Override
        public ObjectSet<Object2LongMap.Entry<NetworkResourceType>> object2LongEntrySet() {
            return entries;
        }
    }

    public static final class Builder {
        private final Object2LongOpenHashMap<NetworkResourceType> flat = new Object2LongOpenHashMap<>();
        private final Object2LongOpenHashMap<NetworkResourceType> multiplierPpm = new Object2LongOpenHashMap<>();
        private final Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType> metadata = new Object2ObjectOpenHashMap<>();

        private Builder() {
        }

        public Builder flat(NetworkResourceType type, long amount) {
            add(flat, type, amount);
            return this;
        }

        public Builder multiplierPpm(NetworkResourceType type, long amount) {
            add(multiplierPpm, type, amount);
            return this;
        }

        public NetworkResourceModifier build() {
            return new NetworkResourceModifier(this);
        }

        private void add(Object2LongOpenHashMap<NetworkResourceType> target, NetworkResourceType type, long amount) {
            requireConsistentType(type, metadata);
            if (target.containsKey(type)) {
                throw new IllegalArgumentException("Duplicate computing resource adjustment for " + type.id());
            }
            target.put(type, amount);
        }
    }
}
