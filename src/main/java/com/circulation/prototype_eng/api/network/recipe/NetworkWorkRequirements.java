package com.circulation.prototype_eng.api.network.recipe;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchSnapshots;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Immutable machine defaults or per-recipe research and numeric demands for one work unit.
 */
@SuppressWarnings("unused")
public final class NetworkWorkRequirements {
    public static final NetworkWorkRequirements EMPTY = builder().build();

    private final Object2IntMap<ResourceLocation> research;
    private final Reference2DoubleMap<NetworkResourceType> resources;

    private NetworkWorkRequirements(Builder builder) {
        research = ResearchSnapshots.levels(builder.research);
        resources = NetworkResourceModifier.copyResources(builder.resources);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Numeric demands are summed exactly before scaling; a failed sum never returns a partial vector.
     */
    public static NetworkWorkRequirements combined(NetworkWorkRequirements defaults, NetworkWorkRequirements recipe) {
        return builder().addAll(defaults).addAll(recipe).build();
    }

    /**
     * Minimum completed levels; combining defaults and recipe demands retains the highest level.
     */
    public Object2IntMap<ResourceLocation> researchRequirements() {
        return research;
    }

    /**
     * Complete immutable numeric vector at parallelism one; logical concurrency uses machine leases.
     */
    public Reference2DoubleMap<NetworkResourceType> resources() {
        return resources;
    }

    /**
     * Computes the complete exact vector before budget acquisition; overflow cannot hide excess demand.
     */
    public Reference2DoubleMap<NetworkResourceType> scaledResources(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("Work parallelism must be positive");
        }
        return scaledResources((long) parallelism);
    }

    /**
     * A retimed batch slice may have no work or more than int capacity; the full vector remains exact.
     */
    public Reference2DoubleMap<NetworkResourceType> scaledResources(long parallelism) {
        if (parallelism < 0) throw new IllegalArgumentException("Work quantity must be nonnegative");
        var scaled = new Reference2DoubleOpenHashMap<NetworkResourceType>(resources.size());
        for (var entry : resources.reference2DoubleEntrySet()) {
            scaled.put(entry.getKey(), NetworkResourceModifier.product(entry.getDoubleValue(), parallelism));
        }
        return scaled;
    }

    /**
     * Loading-time authority check, also used by machine defaults before runtime assembly.
     */
    public void validate(ResearchRegistry researchRegistry) {
        if (!Objects.requireNonNull(researchRegistry).isFrozen()) {
            throw new IllegalStateException("Work requirements need frozen research and computing registries");
        }
        for (var entry : research.object2IntEntrySet()) {
            if (entry.getIntValue() > researchRegistry.require(entry.getKey()).maxLevel()) {
                throw new IllegalArgumentException("Work research requirement exceeds maxLevel: " + entry.getKey());
            }
        }
    }

    /**
     * Add-only loading context. Registry publication seals callback contexts, including failed ones.
     */
    public static final class Builder {
        private final Object2IntOpenHashMap<ResourceLocation> research = new Object2IntOpenHashMap<>();
        private final Reference2DoubleOpenHashMap<NetworkResourceType> resources = new Reference2DoubleOpenHashMap<>();
        private boolean sealed;

        private Builder() {
        }

        private static void checkAddition(double existing, double amount) {
            NetworkResourceModifier.sum(existing, amount);
        }

        public Builder research(ResourceLocation id, int minimumLevel) {
            requireMutable();
            Objects.requireNonNull(id);
            if (minimumLevel < 1) {
                throw new IllegalArgumentException("Work research level must be positive");
            }
            research.put(id, Math.max(research.getInt(id), minimumLevel));
            return this;
        }

        public Builder resource(NetworkResourceType type, double amountPerWorkUnit) {
            requireMutable();
            checkResource(type, amountPerWorkUnit);
            checkAddition(resources.getDouble(type), amountPerWorkUnit);
            resources.addTo(type, amountPerWorkUnit);
            return this;
        }

        /**
         * Preflights every numeric sum before changing either requirements table.
         */
        public Builder addAll(NetworkWorkRequirements requirements) {
            requireMutable();
            Objects.requireNonNull(requirements);
            for (var entry : requirements.resources.reference2DoubleEntrySet()) {
                checkResource(entry.getKey(), entry.getDoubleValue());
                checkAddition(resources.getDouble(entry.getKey()), entry.getDoubleValue());
            }
            for (var entry : requirements.research.object2IntEntrySet()) {
                research(entry.getKey(), entry.getIntValue());
            }
            for (var entry : requirements.resources.reference2DoubleEntrySet()) {
                resource(entry.getKey(), entry.getDoubleValue());
            }
            return this;
        }

        /**
         * Finishes this context once and returns a detached immutable snapshot.
         */
        public NetworkWorkRequirements build() {
            requireMutable();
            seal();
            return new NetworkWorkRequirements(this);
        }

        void seal() {
            sealed = true;
        }

        private void checkResource(NetworkResourceType type, double amount) {
            Objects.requireNonNull(type);
            if (type.internal()) {
                throw new IllegalArgumentException("Network work cannot demand a computer-local resource: " + type.id());
            }
            if (!Double.isFinite(amount) || amount <= 0) {
                throw new IllegalArgumentException("Work numeric demand must be positive: " + type.id());
            }
        }

        private void requireMutable() {
            if (sealed) {
                throw new IllegalStateException("Work requirement context is sealed");
            }
        }
    }
}
