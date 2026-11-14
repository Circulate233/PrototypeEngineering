package com.circulation.prototype_eng.api.research.view;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Server display snapshot. The producer transfers lists and resource maps read-only.
 */
public record ResearchStationView(String teamId, boolean visible, boolean canManage,
                                  List<TeamResearchQueue.Entry> queue,
                                  @Nullable ResearchProgress.Snapshot progress, long durationTicks,
                                  AEKey2LongMap availableMaterials,
                                  Reference2DoubleMap<NetworkResourceType> resourceTotals,
                                  Reference2DoubleMap<NetworkResourceType> occupiedResources,
                                  Reference2DoubleMap<NetworkResourceType> capacity, int workingStations) {
    public static ResearchStationView unavailable() {
        return new ResearchStationView("", false, false, List.of(), null, 0, ImmutableResourceAmounts.empty(),
            Reference2DoubleMaps.emptyMap(), Reference2DoubleMaps.emptyMap(), Reference2DoubleMaps.emptyMap(), 0);
    }

    public enum Action {UP, DOWN, CURRENT, REMOVE}
}
