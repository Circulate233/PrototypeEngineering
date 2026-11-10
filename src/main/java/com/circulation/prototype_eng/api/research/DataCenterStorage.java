package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * External data-center backend: owner-side writes and versioned detached captures. The host must
 * establish current team membership and mutation authority before supplying a cloud projection;
 * this backend stores physical contents and does not turn an old TeamResearchView into permission.
 */
public interface DataCenterStorage {
    DataCenterResearchSnapshot snapshot();

    /**
     * Owner-only proof of the exact contents, including changes that later restore equal contents.
     */
    boolean current(DataCenterResearchSnapshot snapshot);

    default Capture capture() {
        var snapshot = snapshot();
        return new Capture(snapshot, () -> current(snapshot));
    }

    default Result downloadResearch(ResourceLocation researchId, int requestedLevel, TeamResearchView cloud) {
        return downloadResearch(researchId, requestedLevel, cloud, () -> true);
    }

    /**
     * Recheck the caller's read-only authority immediately before publishing physical changes.
     */
    Result downloadResearch(ResourceLocation researchId, int requestedLevel, TeamResearchView cloud, BooleanSupplier current);

    /**
     * Atomically validate all disk entries before publishing any of them. Item consumption belongs to its caller.
     */
    default Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud) {
        return storeFromDisk(entries, cloud, () -> true);
    }

    Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud, BooleanSupplier current);

    /**
     * Upgrade only already stored multi-level research within the data-center tier. Never lower
     * levels or add missing ids; reject the whole update if an upgrade conflicts with cloud or stored research.
     */
    default Result synchronizeLoopingResearch(TeamResearchView cloud) {
        return synchronizeLoopingResearch(cloud, () -> true);
    }

    Result synchronizeLoopingResearch(TeamResearchView cloud, BooleanSupplier current);

    enum Result {
        UPDATED, UNCHANGED, SPECIAL_ONLY_REJECTED, ITEM_ONLY_REJECTED, MISSING_CLOUD_RESEARCH_LEVEL,
        DATA_CENTER_LEVEL_TOO_LOW, MUTEX_BLOCKED, CAPACITY_FULL, STALE_CONTEXT;

        public boolean accepted() {
            return this == UPDATED || this == UNCHANGED;
        }
    }

    record Capture(DataCenterResearchSnapshot snapshot, BooleanSupplier current) {
        public Capture {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(current);
        }
    }
}
