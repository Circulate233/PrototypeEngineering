package com.circulation.prototype_eng.api.team;

/**
 * Normal outcomes of resolving the current team; corrupt data and caller errors are thrown.
 */
public enum OwnerTeamResolutionStatus {
    RESOLVED,
    UNIVERSE_UNAVAILABLE,
    OWNER_UNKNOWN,
    NO_TEAM,
    UNSUPPORTED_TEAM_TYPE,
    INVALID_TEAM
}
