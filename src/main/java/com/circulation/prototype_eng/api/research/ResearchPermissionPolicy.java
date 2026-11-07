package com.circulation.prototype_eng.api.research;

import java.util.Objects;

/** Shared permission decisions for Java, packets, commands and later script adapters. */
@FunctionalInterface
public interface ResearchPermissionPolicy {
    ResearchPermissionPolicy DEFAULT = ResearchPermissionPolicy::standard;

    Decision can(Action action, ResearchPermissionContext context);

    enum Action {
        VIEW_OWN_RESEARCH, VIEW_TEAM_RESEARCH, REQUEST_DATA_CENTER_STATE, REQUEST_QUEUE_STATE,
        START_RESEARCH, DOWNLOAD_RESEARCH_TO_DATA_CENTER, FORGET_PERSONAL_RESEARCH,
        BLACKLIST_TEAM_RESEARCH, FORGET_ANY_RESEARCH, MODIFY_ANY_RESEARCH_LEVEL
    }

    enum Decision {
        ALLOWED, NOT_TEAM_MEMBER, NOT_TEAM_LEADER, NOT_SERVER_OPERATOR,
        MISSING_ACTOR_TEAM, MISSING_TARGET_TEAM, TARGET_TEAM_MISMATCH, TARGET_PLAYER_MISMATCH
    }

    private static Decision standard(Action action, ResearchPermissionContext context) {
        Objects.requireNonNull(context);
        return switch (Objects.requireNonNull(action)) {
            case VIEW_OWN_RESEARCH -> context.actorId().equals(context.personalTarget()) || context.serverOperator()
                ? Decision.ALLOWED : Decision.TARGET_PLAYER_MISMATCH;
            case VIEW_TEAM_RESEARCH, REQUEST_DATA_CENTER_STATE, REQUEST_QUEUE_STATE,
                 START_RESEARCH, DOWNLOAD_RESEARCH_TO_DATA_CENTER -> teamOperation(context, true);
            case FORGET_PERSONAL_RESEARCH -> context.actorId().equals(context.personalTarget())
                ? teamOperation(context, false) : Decision.TARGET_PLAYER_MISMATCH;
            case BLACKLIST_TEAM_RESEARCH -> {
                var team = teamOperation(context, true);
                yield team != Decision.ALLOWED ? team : context.serverOperator() || context.teamLeader() || context.teamModerator()
                    ? Decision.ALLOWED : Decision.NOT_TEAM_LEADER;
            }
            case FORGET_ANY_RESEARCH, MODIFY_ANY_RESEARCH_LEVEL -> context.serverOperator()
                ? Decision.ALLOWED : Decision.NOT_SERVER_OPERATOR;
        };
    }

    private static Decision teamOperation(ResearchPermissionContext context, boolean operatorOverride) {
        if (context.targetTeamId() == null) { return Decision.MISSING_TARGET_TEAM; }
        if (operatorOverride && context.serverOperator()) { return Decision.ALLOWED; }
        if (context.actorTeamId() == null) { return Decision.MISSING_ACTOR_TEAM; }
        return context.actorTeamId().equals(context.targetTeamId()) ? Decision.ALLOWED : Decision.TARGET_TEAM_MISMATCH;
    }
}
