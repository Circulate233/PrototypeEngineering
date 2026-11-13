package com.circulation.prototype_eng.api.network.resource;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Immutable exact integer adjustment of a team's complete per-tick computing resource vector.
 * Returned native maps and entry views are read-only by contract.
 */
public final class NetworkResourceModifier {
    public static final NetworkResourceModifier EMPTY = new Builder().build();
    private static final long ONE_MILLION = 1_000_000L;

    private final Object2LongMap<NetworkResourceType> flat;
    private final Object2LongMap<NetworkResourceType> multiplierPpm;

    private NetworkResourceModifier(Builder builder) {
        this.flat = new Object2LongOpenHashMap<>(builder.flat);
        this.multiplierPpm = new Object2LongOpenHashMap<>(builder.multiplierPpm);
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
     * Captures a mutable resource vector once. The returned native map and its entries are read-only
     * by contract; callers with an already completed vector validate and borrow it instead.
     */
    public static Object2LongMap<NetworkResourceType> copyResources(Object2LongMap<NetworkResourceType> resources) {
        validateResources(resources);
        var captured = new Object2LongOpenHashMap<>(resources);
        captured.defaultReturnValue(0);
        return captured;
    }

    /**
     * Validates a borrowed completed vector without copying or wrapping its entries.
     */
    public static void validateResources(Object2LongMap<NetworkResourceType> resources) {
        validateResources(resources, new Object2ObjectOpenHashMap<>());
    }

    private static void validateResources(Object2LongMap<NetworkResourceType> source,
                                          Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType> metadata) {
        Objects.requireNonNull(source, "Computing resource vector");
        for (var entries = Object2LongMaps.fastIterator(source); entries.hasNext(); ) {
            var entry = entries.next();
            requireConsistentType(entry.getKey(), metadata);
            if (entry.getLongValue() < 0L) {
                throw new IllegalArgumentException("Computing resource quantity must be non-negative");
            }
        }
    }

    private static void requireConsistentType(NetworkResourceType type,
                                              Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType> metadata) {
        Objects.requireNonNull(type, "Computing resource type");
        var retained = metadata.putIfAbsent(type.id(), type);
        if (retained != null && !retained.equals(type)) {
            throw new IllegalArgumentException("Conflicting computing resource metadata for " + type.id());
        }
    }

    public Object2LongMap<NetworkResourceType> flatAdjustments() {
        return flat;
    }

    public Object2LongMap<NetworkResourceType> multiplierPpmAdjustments() {
        return multiplierPpm;
    }

    /**
     * Applies floor((base + flat) * (1_000_000 + ppm) / 1_000_000), rejecting intermediate overflow.
     * The base vector is borrowed unchanged for this call; the returned completed vector is read-only.
     */
    public Object2LongMap<NetworkResourceType> apply(Object2LongMap<NetworkResourceType> base) {
        var metadata = new Object2ObjectOpenHashMap<ResourceLocation, NetworkResourceType>();
        validateResources(base, metadata);
        for (var type : flat.keySet()) {
            requireConsistentType(type, metadata);
        }
        for (var type : multiplierPpm.keySet()) {
            requireConsistentType(type, metadata);
        }
        var result = new Object2LongOpenHashMap<NetworkResourceType>(metadata.size());
        result.defaultReturnValue(0L);
        for (var type : metadata.values()) {
            long adjustedBase = Math.addExact(base.getOrDefault(type, 0L), flat.getLong(type));
            long factor = Math.addExact(ONE_MILLION, multiplierPpm.getLong(type));
            long numerator = Math.multiplyExact(adjustedBase, factor);
            if (adjustedBase < 0L) {
                throw new IllegalArgumentException("Computing resource modifier produces a negative value for " + type.id());
            }
            result.put(type, Math.floorDiv(numerator, ONE_MILLION));
        }
        return result;
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
