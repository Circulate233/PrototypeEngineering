package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;

import java.util.Objects;

/**
 * Network demands expressed as totals or per-station tick limits, converted using the stage duration.
 * Resource keys use object identity. Reuse singletons and do not modify returned definitions.
 */
@SuppressWarnings("unused")
public final class ResearchResourceFormula {
    private final Reference2DoubleMap<NetworkResourceType> baseDemands;
    private final Reference2DoubleMap<NetworkResourceType> demandSlopes;
    private final Reference2DoubleMap<NetworkResourceType> tickDemands;
    private final Reference2DoubleMap<NetworkResourceType> tickSlopes;

    private ResearchResourceFormula(Builder builder) {
        baseDemands = immutable(builder.baseDemands);
        demandSlopes = immutable(builder.demandSlopes);
        tickDemands = immutable(builder.tickDemands);
        tickSlopes = immutable(builder.tickSlopes);
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

    public boolean isEmpty() {
        return baseDemands.isEmpty() && tickDemands.isEmpty();
    }

    public Reference2DoubleMap<NetworkResourceType> demandsAt(int stageFirstLevel, int level, long durationTicks) {
        if (stageFirstLevel < 1 || level < stageFirstLevel) {
            throw new IllegalArgumentException("Research formula level precedes its positive stage start");
        }
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>(baseDemands.size());
        long offset = (long) level - stageFirstLevel;
        for (var entry : baseDemands.reference2DoubleEntrySet()) {
            result.put(entry.getKey(), exact(entry.getKey(), entry.getDoubleValue(), demandSlopes.getDouble(entry.getKey()), offset));
        }
        for (var entry : tickDemands.reference2DoubleEntrySet()) {
            double amount = NetworkResourceModifier.product(exact(entry.getKey(), entry.getDoubleValue(), tickSlopes.getDouble(entry.getKey()), offset), durationTicks);
            NetworkResourceModifier.sum(result.getDouble(entry.getKey()), amount);
            result.addTo(entry.getKey(), amount);
        }
        return result;
    }

    /**
     * Nonnegative slopes make the endpoints the extrema, including an Integer.MAX_VALUE tail.
     */
    public void validateRange(int firstLevel, int lastLevel, long durationTicks) {
        if (firstLevel < 1 || lastLevel < firstLevel) {
            throw new IllegalArgumentException("Invalid research formula range " + firstLevel + ".." + lastLevel);
        }
        demandsAt(firstLevel, lastLevel, durationTicks);
    }

    public static final class Builder {
        private final Reference2DoubleOpenHashMap<NetworkResourceType> baseDemands = new Reference2DoubleOpenHashMap<>();
        private final Reference2DoubleOpenHashMap<NetworkResourceType> demandSlopes = new Reference2DoubleOpenHashMap<>();
        private final Reference2DoubleOpenHashMap<NetworkResourceType> tickDemands = new Reference2DoubleOpenHashMap<>();
        private final Reference2DoubleOpenHashMap<NetworkResourceType> tickSlopes = new Reference2DoubleOpenHashMap<>();

        private Builder() {
        }

        public Builder constant(NetworkResourceType type, double demand) {
            return affine(type, demand, 0);
        }

        public Builder affine(NetworkResourceType type, double base, double slope) {
            return add(type, base, slope, baseDemands, demandSlopes);
        }

        public Builder perTick(NetworkResourceType type, double limit) {
            return affinePerTick(type, limit, 0);
        }

        public Builder affinePerTick(NetworkResourceType type, double base, double slope) {
            return add(type, base, slope, tickDemands, tickSlopes);
        }

        private Builder add(NetworkResourceType type, double base, double slope,
                            Reference2DoubleOpenHashMap<NetworkResourceType> values,
                            Reference2DoubleOpenHashMap<NetworkResourceType> slopes) {
            Objects.requireNonNull(type);
            if (!Double.isFinite(base) || !Double.isFinite(slope) || base < 0 || slope < 0 || type.internal()) {
                throw new IllegalArgumentException("Invalid numeric research demand for " + type.id());
            }
            NetworkResourceModifier.sum(values.getDouble(type), base);
            NetworkResourceModifier.sum(slopes.getDouble(type), slope);
            values.addTo(type, base);
            slopes.addTo(type, slope);
            return this;
        }

        public ResearchResourceFormula build() {
            return new ResearchResourceFormula(this);
        }
    }
}
