package com.circulation.prototype_eng.api.research;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Current server-captured actor, target and roles. Packet fields must never supply the role flags.
 */
public record ResearchPermissionContext(UUID actorId, @Nullable String actorTeamId,
                                        @Nullable UUID targetPlayerId, @Nullable String targetTeamId,
                                        boolean teamLeader, boolean teamModerator, boolean serverOperator) {
    public ResearchPermissionContext {
        requireTeamId(actorTeamId);
        requireTeamId(targetTeamId);
    }

    private static void requireTeamId(@Nullable String teamId) {
        if (teamId != null && (teamId.isBlank() || !teamId.equals(teamId.trim()))) {
            throw new IllegalArgumentException("Permission team id must be canonical non-blank text");
        }
    }

    public UUID personalTarget() {
        return targetPlayerId == null ? actorId : targetPlayerId;
    }
}
