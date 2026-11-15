package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.util.ResourceLocation;


/**
 * Immutable research disk contents. Research levels record completed progress; disk tier limits the
 * research tier of each recorded level, rather than that completed level's numeric value.
 * Entries are completed zero-default data borrowed read-only for this object's lifetime.
 */
@SuppressWarnings("unused") // Public declaration and item integration boundary.
public final class ResearchDiskData {
    /**
     * Retains the old PN payload bound as a serialization limit, not a gameplay capacity.
     */
    public static final int MAX_ENTRIES = 256;

    private final ResearchRegistry registry;
    private final int tier;
    private final Object2IntMap<ResourceLocation> entries;

    public ResearchDiskData(ResearchRegistry registry, int tier, Object2IntMap<ResourceLocation> entries) {
        this.registry = registry;
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research disk requires a frozen research registry");
        }
        if (tier < 0) {
            throw new IllegalArgumentException("Research disk tier must be non-negative");
        }
        this.tier = tier;
        this.entries = entries;
        if (this.entries.isEmpty() || this.entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Research disk requires 1.." + MAX_ENTRIES + " distinct research entries");
        }
        for (var entry : this.entries.object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            if (definition.researchTierForLevel(entry.getIntValue()) > tier) {
                throw new IllegalArgumentException("Research tier exceeds disk tier: " + definition.id());
            }
        }
    }

    public int tier() {
        return tier;
    }

    public Object2IntMap<ResourceLocation> entries() {
        return entries;
    }

    void requireRegistry(ResearchRegistry expected) {
        if (registry != expected) {
            throw new IllegalArgumentException("Research disk belongs to another definition registry");
        }
    }
}
