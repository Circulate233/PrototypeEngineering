package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * PN's stage-relative exact affine demand, with a separate limit for each committed segment.
 */
@SuppressWarnings("unused")
public final class ResearchResourceFormula {
    private final Object2LongMap<NetworkResourceType> baseDemands;
    private final Object2LongMap<NetworkResourceType> demandSlopes;
    private final Object2LongMap<NetworkResourceType> segmentGrantLimits;

    private ResearchResourceFormula(Builder builder) {
        baseDemands = immutable(builder.baseDemands);
        demandSlopes = immutable(builder.demandSlopes);
        segmentGrantLimits = immutable(builder.segmentGrantLimits);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static long exact(NetworkResourceType type, long base, long slope, long offset) {
        try {
            return Math.addExact(base, Math.multiplyExact(slope, offset));
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Research demand overflows for " + type.id(), failure);
        }
    }

    private static Object2LongMap<NetworkResourceType> immutable(Object2LongMap<NetworkResourceType> source) {
        return NetworkResourceModifier.copyResources(source);
    }

    public Object2LongMap<NetworkResourceType> baseDemands() {
        return baseDemands;
    }

    public Object2LongMap<NetworkResourceType> demandSlopes() {
        return demandSlopes;
    }

    public Object2LongMap<NetworkResourceType> segmentGrantLimits() {
        return segmentGrantLimits;
    }

    public Object2LongMap<NetworkResourceType> demandsAt(int stageFirstLevel, int level) {
        if (stageFirstLevel < 1 || level < stageFirstLevel) {
            throw new IllegalArgumentException("Research formula level precedes its positive stage start");
        }
        var result = new Object2LongOpenHashMap<NetworkResourceType>(baseDemands.size());
        long offset = (long) level - stageFirstLevel;
        for (var entry : baseDemands.object2LongEntrySet()) {
            result.put(entry.getKey(), exact(entry.getKey(), entry.getLongValue(), demandSlopes.getLong(entry.getKey()), offset));
        }
        return result;
    }

    /**
     * Nonnegative slopes make the endpoints the extrema, including an Integer.MAX_VALUE tail.
     */
    public void validateRange(int firstLevel, int lastLevel) {
        if (firstLevel < 1 || lastLevel < firstLevel) {
            throw new IllegalArgumentException("Invalid research formula range " + firstLevel + ".." + lastLevel);
        }
        for (var entry : baseDemands.object2LongEntrySet()) {
            exact(entry.getKey(), entry.getLongValue(), demandSlopes.getLong(entry.getKey()), (long) lastLevel - firstLevel);
        }
    }

    void validateResources(NetworkResourceRegistry registry) {
        for (var type : baseDemands.keySet()) {
            registry.canonical(type);
        }
    }

    public static final class Builder {
        private final Object2LongOpenHashMap<NetworkResourceType> baseDemands = new Object2LongOpenHashMap<>();
        private final Object2LongOpenHashMap<NetworkResourceType> demandSlopes = new Object2LongOpenHashMap<>();
        private final Object2LongOpenHashMap<NetworkResourceType> segmentGrantLimits = new Object2LongOpenHashMap<>();
        private final ObjectOpenHashSet<ResourceLocation> ids = new ObjectOpenHashSet<>();

        private Builder() {
        }

        public Builder constant(NetworkResourceType type, long demand, long segmentGrantLimit) {
            return affine(type, demand, 0, segmentGrantLimit);
        }

        public Builder affine(NetworkResourceType type, long base, long slope, long segmentGrantLimit) {
            Objects.requireNonNull(type);
            if (type.logicalConcurrency() || base < 0 || slope < 0 || segmentGrantLimit <= 0
                || type.maxPerTickValue() > 0 && segmentGrantLimit > type.maxPerTickValue()) {
                throw new IllegalArgumentException("Invalid numeric research formula or segment limit for " + type.id());
            }
            if (!ids.add(type.id())) {
                throw new IllegalArgumentException("Duplicate research formula resource " + type.id());
            }
            baseDemands.put(type, base);
            demandSlopes.put(type, slope);
            segmentGrantLimits.put(type, segmentGrantLimit);
            return this;
        }

        public ResearchResourceFormula build() {
            return new ResearchResourceFormula(this);
        }
    }
}
