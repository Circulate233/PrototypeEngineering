package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Frozen result of one discovery batch; publication must still prove current runtime ownership.
 * The planner transfers its completed maps; consumers must not mutate maps or entry views.
 */
public final class NetworkPlan {
    private final long epoch;
    private final Map<UUID, NetworkWorkState> workStates;
    private final Map<UUID, String> teamIds;
    private final Map<String, Team> teams;

    NetworkPlan(long epoch, Object2ObjectMap<UUID, NetworkWorkState> workStates,
                Object2ObjectMap<UUID, String> teamIds, Object2ObjectMap<String, Team> teams) {
        this.epoch = epoch;
        this.workStates = workStates;
        this.teamIds = teamIds;
        this.teams = teams;
    }

    public long epoch() {
        return epoch;
    }

    public Map<UUID, NetworkWorkState> workStates() {
        return workStates;
    }

    public Map<UUID, String> teamIds() {
        return teamIds;
    }

    public Map<String, Team> teams() {
        return teams;
    }

    /**
     * Resource quantities are borrowed zero-default immutable data for this plan's lifetime.
     */
    public record Team(UUID centerId, int centerLevel, @Nullable UUID dataCenterId,
                       Object2LongMap<NetworkResourceType> resourcesPerTick,
                       @Nullable DataCenterResearchSnapshot dataCenterResearch) {
        public Team {
            Objects.requireNonNull(centerId);
            if (centerLevel < 1) {
                throw new IllegalArgumentException("Invalid selected center level");
            }
            if (dataCenterResearch != null && !dataCenterResearch.dataCenterId().equals(dataCenterId)) {
                throw new IllegalArgumentException("Selected data-center contents have another identity");
            }
            NetworkResourceModifier.validateResources(resourcesPerTick);
        }

        public Team(UUID centerId, int centerLevel, @Nullable UUID dataCenterId, Object2LongMap<NetworkResourceType> resourcesPerTick) {
            this(centerId, centerLevel, dataCenterId, resourcesPerTick, null);
        }
    }
}
