package com.circulation.prototype_eng.api.team;

import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable owner and current team snapshot, detached from the provider's live state.
 */
public final class OwnerTeamResolution {
    private final UUID ownerId;
    private final OwnerTeamResolutionStatus status;
    private final String teamId;
    private final OwnerTeamType teamType;
    private final ObjectSet<UUID> members;
    private final @Nullable UUID teamOwnerId;
    private final String message;

    private OwnerTeamResolution(UUID ownerId, OwnerTeamResolutionStatus status, String teamId,
                                OwnerTeamType teamType, ObjectSet<UUID> members,
                                @Nullable UUID teamOwnerId, String message) {
        this.ownerId = ownerId;
        this.status = status;
        this.teamId = teamId;
        this.teamType = teamType;
        this.members = members;
        this.teamOwnerId = teamOwnerId;
        this.message = message;
    }

    /**
     * Membership is completed read-only data, retained unchanged for the snapshot lifetime.
     * Server teams may have a null team owner.
     */
    public static OwnerTeamResolution resolved(UUID ownerId, String teamId, OwnerTeamType teamType,
                                               ObjectSet<UUID> members, @Nullable UUID teamOwnerId) {
        return new OwnerTeamResolution(ownerId, OwnerTeamResolutionStatus.RESOLVED, teamId, teamType,
            members, teamOwnerId, "");
    }

    /**
     * Creates a normal denial without exposing any accepted-team payload.
     */
    public static OwnerTeamResolution rejected(UUID ownerId, OwnerTeamResolutionStatus status, String message) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        if (status == OwnerTeamResolutionStatus.RESOLVED || message.isBlank()) {
            throw new IllegalArgumentException("Rejected resolution requires a rejection status and failure context");
        }
        return new OwnerTeamResolution(ownerId, status, null, null, null, null, message.trim());
    }

    public UUID ownerId() {
        return ownerId;
    }

    public OwnerTeamResolutionStatus status() {
        return status;
    }

    public boolean accepted() {
        return status == OwnerTeamResolutionStatus.RESOLVED;
    }

    @SuppressWarnings("unused") // Public diagnostics for consumers of custom providers.
    public String message() {
        return message;
    }

    public String teamId() {
        requireAccepted();
        return teamId;
    }

    public OwnerTeamType teamType() {
        requireAccepted();
        return teamType;
    }

    public ObjectSet<UUID> members() {
        requireAccepted();
        return members;
    }

    public @Nullable UUID teamOwnerId() {
        requireAccepted();
        return teamOwnerId;
    }

    private void requireAccepted() {
        if (!accepted()) {
            throw new IllegalStateException("Team payload is unavailable for " + status);
        }
    }
}
