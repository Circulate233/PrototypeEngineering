package com.circulation.prototype_eng.api.network.resource;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * Double adjustments use resource singleton identity. Completed maps are borrowed read-only.
 */
public final class NetworkResourceModifier {
    public static final NetworkResourceModifier EMPTY = new Builder().build();
    private static final double ONE_MILLION = 1_000_000D;
    private final Reference2DoubleMap<NetworkResourceType> flat;
    private final Reference2DoubleMap<NetworkResourceType> multiplierPpm;

    private NetworkResourceModifier(Builder builder) {
        flat = new Reference2DoubleOpenHashMap<>(builder.flat);
        multiplierPpm = new Reference2DoubleOpenHashMap<>(builder.multiplierPpm);
        for (double ppm : multiplierPpm.values())
            if (ONE_MILLION + ppm < 0) throw new IllegalArgumentException("Negative computing multiplier factor");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static double sum(double first, double second) {
        double value = first + second;
        if (!Double.isFinite(value)) throw new ArithmeticException("Computing resource sum overflows");
        return value;
    }

    public static double product(double first, double second) {
        double value = first * second;
        if (!Double.isFinite(value)) throw new ArithmeticException("Computing resource product overflows");
        return value;
    }

    /**
     * Capture only an actually mutable source; completed vectors are borrowed instead.
     */
    public static Reference2DoubleMap<NetworkResourceType> copyResources(Reference2DoubleMap<NetworkResourceType> resources) {
        validateResources(resources);
        return new Reference2DoubleOpenHashMap<>(resources);
    }

    public static void validateResources(Reference2DoubleMap<NetworkResourceType> resources) {
        for (var entries = Reference2DoubleMaps.fastIterator(resources); entries.hasNext(); ) {
            double quantity = entries.next().getDoubleValue();
            if (!Double.isFinite(quantity) || quantity < 0)
                throw new IllegalArgumentException("Resource quantities must be finite and nonnegative");
        }
    }

    @SuppressWarnings("unused") // Public modifier-inspection API for addons.
    public Reference2DoubleMap<NetworkResourceType> flatAdjustments() {
        return flat;
    }

    @SuppressWarnings("unused") // Public modifier-inspection API for addons.
    public Reference2DoubleMap<NetworkResourceType> multiplierPpmAdjustments() {
        return multiplierPpm;
    }

    /**
     * Apply (base + flat) * (1 + ppm / 1_000_000), preserving fractional quantities.
     */
    public Reference2DoubleMap<NetworkResourceType> apply(Reference2DoubleMap<NetworkResourceType> base) {
        var types = new ReferenceOpenHashSet<NetworkResourceType>();
        types.addAll(base.keySet());
        types.addAll(flat.keySet());
        types.addAll(multiplierPpm.keySet());
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>(types.size());
        for (var type : types) result.put(type, apply(type, base.getDouble(type)));
        return result;
    }

    public double apply(NetworkResourceType type, double base) {
        double adjusted = sum(base, flat.getDouble(type));
        if (adjusted < 0) throw new IllegalArgumentException("Computing modifier produces a negative value");
        double factor = 1 + multiplierPpm.getDouble(type) / ONE_MILLION;
        return factor == 1 ? adjusted : product(adjusted, factor);
    }

    public static final class Builder {
        private final Reference2DoubleOpenHashMap<NetworkResourceType> flat = new Reference2DoubleOpenHashMap<>();
        private final Reference2DoubleOpenHashMap<NetworkResourceType> multiplierPpm = new Reference2DoubleOpenHashMap<>();

        private Builder() {
        }

        public Builder flat(NetworkResourceType type, double amount) {
            add(flat, type, amount);
            return this;
        }

        public Builder multiplierPpm(NetworkResourceType type, double amount) {
            add(multiplierPpm, type, amount);
            return this;
        }

        public NetworkResourceModifier build() {
            return new NetworkResourceModifier(this);
        }

        private static void add(Reference2DoubleOpenHashMap<NetworkResourceType> target, NetworkResourceType type, double amount) {
            sum(target.getDouble(type), amount);
            target.addTo(type, amount);
        }
    }
}
