package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;

import java.util.Objects;

/**
 * Stage-relative exact affine total demand. Advancement rates belong to research stations.
 */
@SuppressWarnings("unused")
public final class ResearchResourceFormula {
    private final Reference2DoubleMap<NetworkResourceType> baseDemands;
    private final Reference2DoubleMap<NetworkResourceType> demandSlopes;

    private ResearchResourceFormula(Builder builder) {
        baseDemands = immutable(builder.baseDemands);
        demandSlopes = immutable(builder.demandSlopes);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static double exact(NetworkResourceType type, double base, double slope, long offset) {
        try {
            return NetworkResourceModifier.sum(base, NetworkResourceModifier.product(slope, offset));
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Research demand overflows for " + type.id(), failure);
        }
    }

    private static Reference2DoubleMap<NetworkResourceType> immutable(Reference2DoubleMap<NetworkResourceType> source) {
        return NetworkResourceModifier.copyResources(source);
    }

    public Reference2DoubleMap<NetworkResourceType> baseDemands() {
        return baseDemands;
    }

    public Reference2DoubleMap<NetworkResourceType> demandSlopes() {
        return demandSlopes;
    }

    public Reference2DoubleMap<NetworkResourceType> demandsAt(int stageFirstLevel, int level) {
        if (stageFirstLevel < 1 || level < stageFirstLevel) {
            throw new IllegalArgumentException("Research formula level precedes its positive stage start");
        }
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>(baseDemands.size());
        long offset = (long) level - stageFirstLevel;
        for (var entry : baseDemands.reference2DoubleEntrySet()) {
            result.put(entry.getKey(), exact(entry.getKey(), entry.getDoubleValue(), demandSlopes.getDouble(entry.getKey()), offset));
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
        for (var entry : baseDemands.reference2DoubleEntrySet()) {
            exact(entry.getKey(), entry.getDoubleValue(), demandSlopes.getDouble(entry.getKey()), (long) lastLevel - firstLevel);
        }
    }

    public static final class Builder {
        private final Reference2DoubleOpenHashMap<NetworkResourceType> baseDemands = new Reference2DoubleOpenHashMap<>();
        private final Reference2DoubleOpenHashMap<NetworkResourceType> demandSlopes = new Reference2DoubleOpenHashMap<>();

        private Builder() {
        }

        public Builder constant(NetworkResourceType type, double demand) {
            return affine(type, demand, 0);
        }

        public Builder affine(NetworkResourceType type, double base, double slope) {
            Objects.requireNonNull(type);
            if (!Double.isFinite(base) || !Double.isFinite(slope) || base < 0 || slope < 0 || type.internal()) {
                throw new IllegalArgumentException("Invalid numeric research demand for " + type.id());
            }
            NetworkResourceModifier.sum(baseDemands.getDouble(type), base);
            NetworkResourceModifier.sum(demandSlopes.getDouble(type), slope);
            baseDemands.addTo(type, base);
            demandSlopes.addTo(type, slope);
            return this;
        }

        public ResearchResourceFormula build() {
            return new ResearchResourceFormula(this);
        }
    }
}
