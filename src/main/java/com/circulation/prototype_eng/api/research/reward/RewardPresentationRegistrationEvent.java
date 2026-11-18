package com.circulation.prototype_eng.api.research.reward;

import net.minecraftforge.fml.common.eventhandler.Event;

public final class RewardPresentationRegistrationEvent extends Event {
    private final RewardPresentationRegistry registry;

    public RewardPresentationRegistrationEvent(RewardPresentationRegistry registry) {
        this.registry = registry;
    }

    public RewardPresentationRegistry registry() {
        return registry;
    }
}
