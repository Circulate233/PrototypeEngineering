package com.circulation.prototype_eng.api.research.reward;

/**
 * An external event that durably deduplicates the supplied key. Retrying after an unknown result
 * must return the existing durable result without repeating the effect; a plain Consumer is unsafe.
 */
@FunctionalInterface
public interface RewardEventAction {
    Result deliver(RewardEventKey key, ResearchRewardContext context);

    enum Result {APPLIED, ALREADY_APPLIED, RETRY_LATER}
}
