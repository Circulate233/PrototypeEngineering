package com.circulation.prototype_eng.api.research.view;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Detached owner-side database view; an action must capture its own current authority.
 */
public record DataCenterPlayerView(String teamId, Decision viewPermission, Decision downloadPermission,
                                   boolean savePending, @Nullable DataCenterResearchSnapshot dataCenter,
                                   @Nullable TeamResearchView cloud) {
    public DataCenterPlayerView {
        Objects.requireNonNull(teamId);
        Objects.requireNonNull(viewPermission);
        Objects.requireNonNull(downloadPermission);
        if (viewPermission != Decision.ALLOWED && (dataCenter != null || cloud != null)) {
            throw new IllegalArgumentException("Denied database views cannot expose research");
        }
        if (cloud != null && !teamId.equals(cloud.teamId())) {
            throw new IllegalArgumentException("Database cloud belongs to another team");
        }
    }
}
