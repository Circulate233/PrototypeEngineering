package com.circulation.prototype_eng.api.research.reward;

/**
 * Synchronous durability barrier. Returning certifies this complete claim has reached durable
 * player/world storage. A failure stops further effects; the next attempt checkpoints again first.
 */
@FunctionalInterface
public interface RewardClaimCheckpoint {
    void persist(RewardClaim claim);
}
