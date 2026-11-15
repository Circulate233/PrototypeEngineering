package com.circulation.prototype_eng.api.research.reward;

import ae2.api.stacks.AEKey;
import net.minecraft.util.ResourceLocation;


/**
 * Ordered executable children of a single selectable group. IDs must remain stable in saves.
 */
public sealed interface RewardChild {
    ResourceLocation id();

    record Event(ResourceLocation id, RewardEventAction action) implements RewardChild {
    }

    /**
     * AEKey is the complete business resource identity, including its components.
     */
    record Item(ResourceLocation id, AEKey resource, long amount) implements RewardChild {
        public Item {
            if (amount < 1) throw new IllegalArgumentException("Reward amount must be positive");
        }
    }
}
