package com.circulation.prototype_eng.api.network;

/** A topology decision, not a research, resource reservation or executable worker permit. */
public enum NetworkWorkState {
    ACTIVE_CENTER, ACTIVE_DATA_CENTER, ACTIVE_DEVICE,
    UNFORMED, OWNER_TEAM_REJECTED, NO_ACTIVE_CENTER, CENTER_LEVEL_INSUFFICIENT, SAME_TYPE_CONFLICT
}
