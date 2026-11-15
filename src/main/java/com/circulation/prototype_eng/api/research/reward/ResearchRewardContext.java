package com.circulation.prototype_eng.api.research.reward;

import com.circulation.prototype_eng.api.research.ResearchDefinition;

import java.util.UUID;

/**
 * The current authoritative reward attempt, supplied after the selection checkpoint.
 */
public record ResearchRewardContext(UUID playerUuid, ResearchDefinition definition, int level,
                                    long currentWorldTime) {
    public ResearchRewardContext {
        if (level < 1 || currentWorldTime < 0) throw new IllegalArgumentException("Invalid reward context");
    }
}
