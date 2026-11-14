package com.circulation.prototype_eng.api.network.recipe;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.util.ResourceLocation;

/**
 * Immutable machine defaults or per-recipe research and numeric demands for one work unit.
 */
@SuppressWarnings("unused")
public final class NetworkWorkRequirements {
    public static final NetworkWorkRequirements EMPTY = builder().build();

    private final Object2IntMap<ResourceLocation> research;
    private final Reference2DoubleMap<NetworkResourceType> resources;

    private NetworkWorkRequirements(Builder builder) {
        research = builder.research;
        resources = builder.resources;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Numeric demands are summed exactly before scaling; a failed sum never returns a partial vector.
     */
    public static NetworkWorkRequirements combined(NetworkWorkRequirements defaults, NetworkWorkRequirements recipe) {
        if (defaults.research.isEmpty() && defaults.resources.isEmpty()) return recipe;
        if (recipe.research.isEmpty() && recipe.resources.isEmpty()) return defaults;
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
        if (parallelism == 1 || resources.isEmpty()) return resources;
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
        if (!researchRegistry.isFrozen()) {
            throw new IllegalStateException("Work requirements need frozen research and computing registries");
        }
        for (var entry : research.object2IntEntrySet()) {
            if (entry.getIntValue() > researchRegistry.require(entry.getKey()).maxLevel()) {
                throw new IllegalArgumentException("Work research requirement exceeds maxLevel: " + entry.getKey());
            }
        }
    }

    /**
     * Add-only loading context. Do not retain callback builders or modify them after build().
     */
    public static final class Builder {
        private final Object2IntOpenHashMap<ResourceLocation> research = new Object2IntOpenHashMap<>();
        private final Reference2DoubleOpenHashMap<NetworkResourceType> resources = new Reference2DoubleOpenHashMap<>();

        private Builder() {
        }


        public Builder research(ResourceLocation id, int minimumLevel) {
            if (minimumLevel < 1) {
                throw new IllegalArgumentException("Work research level must be positive");
            }
            research.put(id, Math.max(research.getInt(id), minimumLevel));
            return this;
        }

        public Builder resource(NetworkResourceType type, double amountPerWorkUnit) {
            checkResource(type, amountPerWorkUnit);
            NetworkResourceModifier.sum(resources.getDouble(type), amountPerWorkUnit);
            resources.addTo(type, amountPerWorkUnit);
            return this;
        }

        /**
         * Preflights every numeric sum before changing either requirements table.
         */
        public Builder addAll(NetworkWorkRequirements requirements) {
            for (var entry : requirements.resources.reference2DoubleEntrySet()) {
                NetworkResourceModifier.sum(resources.getDouble(entry.getKey()), entry.getDoubleValue());
            }
            for (var entry : requirements.research.object2IntEntrySet()) {
                research.put(entry.getKey(), Math.max(research.getInt(entry.getKey()), entry.getIntValue()));
            }
            for (var entry : requirements.resources.reference2DoubleEntrySet()) {
                resources.addTo(entry.getKey(), entry.getDoubleValue());
            }
            return this;
        }

        /**
         * Transfers completed maps to the result. The builder must not be used afterward.
         */
        public NetworkWorkRequirements build() {
            return new NetworkWorkRequirements(this);
        }


        private void checkResource(NetworkResourceType type, double amount) {
            if (type.internal()) {
                throw new IllegalArgumentException("Network work cannot demand a computer-local resource: " + type.id());
            }
            if (!Double.isFinite(amount) || amount <= 0) {
                throw new IllegalArgumentException("Work numeric demand must be positive: " + type.id());
            }
        }


    }
}
