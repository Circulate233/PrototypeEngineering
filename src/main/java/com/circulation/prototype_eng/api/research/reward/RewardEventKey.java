package com.circulation.prototype_eng.api.research.reward;

import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKey;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable identity of one player's selected reward child, including across restarts.
 */
public record RewardEventKey(UUID playerUuid, ResourceLocation researchId, int level,
                             ResourceLocation groupId, ResourceLocation childId) implements ItemDeliveryKey {
    public RewardEventKey {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(researchId, "researchId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(childId, "childId");
        if (level < 1) throw new IllegalArgumentException("Reward level must be positive");
    }
}
