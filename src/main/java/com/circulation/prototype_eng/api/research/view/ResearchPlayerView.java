package com.circulation.prototype_eng.api.research.view;

import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One player's detached display data; it contains no execution plans or recovery receipts.
 * Collections are completed immutable data borrowed for the view's lifetime. Maps use zero default
 * values; producers and consumers must not modify any collection or entry view after handoff.
 */
public record ResearchPlayerView(UUID playerUuid, long serverTime, boolean savePending,
                                 Decision personalPermission, Object2IntMap<ResourceLocation> personalLevels,
                                 Team team, ObjectList<ClaimSummary> claims, Decision forgetPermission,
                                 Decision adminPermission) {
    public ResearchPlayerView {
        Objects.requireNonNull(playerUuid);
        if (serverTime < 0) throw new IllegalArgumentException("Negative research view time");
        Objects.requireNonNull(personalPermission);
        Objects.requireNonNull(forgetPermission);
        Objects.requireNonNull(adminPermission);
        validateLevels(personalLevels);
        Objects.requireNonNull(team);
        Objects.requireNonNull(claims);
        if (personalPermission != Decision.ALLOWED && (!personalLevels.isEmpty() || !claims.isEmpty())) {
            throw new IllegalArgumentException("Denied personal view contains private data");
        }
    }

    /**
     * Compatibility constructor: an older detached view grants no new operation permissions.
     */
    public ResearchPlayerView(UUID playerUuid, long serverTime, boolean savePending,
                              Decision personalPermission, Object2IntMap<ResourceLocation> personalLevels,
                              Team team, ObjectList<ClaimSummary> claims) {
        this(playerUuid, serverTime, savePending, personalPermission, personalLevels, team, claims,
            Decision.TARGET_PLAYER_MISMATCH, Decision.NOT_SERVER_OPERATOR);
    }

    private static void validateLevels(Object2IntMap<ResourceLocation> source) {
        for (var entry : Objects.requireNonNull(source).object2IntEntrySet()) {
            Objects.requireNonNull(entry.getKey());
            if (entry.getIntValue() < 0) throw new IllegalArgumentException("Negative research view level");
        }
    }

    private static <T> void validateCounts(Object2LongMap<T> source) {
        for (var entry : source.object2LongEntrySet()) {
            Objects.requireNonNull(entry.getKey());
            if (entry.getLongValue() < 0) throw new IllegalArgumentException("Negative research view progress");
        }
    }

    public enum TeamStatus {
        AVAILABLE, MISSING_TEAM, UNAVAILABLE, DENIED;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final TeamStatus[] VALUES = values();
    }

    /**
     * Structural targets describe prerequisites only; admission still evaluates the real actor.
     */
    public record Team(TeamStatus status, String id, Decision viewPermission, Decision queuePermission,
                       Decision startPermission, Decision blacklistPermission,
                       Object2IntMap<ResourceLocation> sharedLevels,
                       Object2IntMap<ResourceLocation> structuralTargets, ObjectSet<ResourceLocation> blacklist,
                       long queueEpoch, ObjectList<QueueRow> queueRows,
                       Object2LongMap<ResourceLocation> numericProgress, ObjectList<ManualRow> manualRows) {
        public Team {
            Objects.requireNonNull(status);
            Objects.requireNonNull(id);
            Objects.requireNonNull(viewPermission);
            Objects.requireNonNull(queuePermission);
            Objects.requireNonNull(startPermission);
            Objects.requireNonNull(blacklistPermission);
            if (!id.equals(id.trim()) || queueEpoch < 0)
                throw new IllegalArgumentException("Invalid research team view");
            validateLevels(sharedLevels);
            validateLevels(structuralTargets);
            Objects.requireNonNull(blacklist);
            Objects.requireNonNull(queueRows);
            validateCounts(numericProgress);
            Objects.requireNonNull(manualRows);
            if (viewPermission != Decision.ALLOWED && (!sharedLevels.isEmpty() || !structuralTargets.isEmpty() || !blacklist.isEmpty())) {
                throw new IllegalArgumentException("Denied team view contains research data");
            }
            if (queuePermission != Decision.ALLOWED && (queueEpoch != 0 || !queueRows.isEmpty() || !numericProgress.isEmpty() || !manualRows.isEmpty())) {
                throw new IllegalArgumentException("Denied queue view contains queue data");
            }
        }
    }

    public record QueueRow(long entryId, ResourceLocation researchId, int targetLevel,
                           UUID requestedBy, long createdAtWorldTime) {
        public QueueRow {
            Objects.requireNonNull(researchId);
            Objects.requireNonNull(requestedBy);
            if (entryId < 1 || targetLevel < 1 || createdAtWorldTime < 0)
                throw new IllegalArgumentException("Invalid research queue row");
        }
    }

    /**
     * Paid material counts are displayable; pending deltas and player inventory receipts are omitted.
     */
    public record ManualRow(long entryId, ManualResearchSession.Status status,
                            @Nullable ManualResearchSession.PauseReason pauseReason, @Nullable UUID contributor,
                            Object2LongMap<ManualMaterialKey> work) {
        public ManualRow {
            if (entryId < 1) throw new IllegalArgumentException("Invalid manual research row");
            Objects.requireNonNull(status);
            validateCounts(work);
        }
    }

    public record ClaimSummary(ResourceLocation researchId, int level, RewardClaim.Status status,
                               Optional<ResourceLocation> selectedGroupId,
                               ObjectList<ResourceLocation> choiceGroupIds) {
        public ClaimSummary {
            Objects.requireNonNull(researchId);
            Objects.requireNonNull(status);
            Objects.requireNonNull(selectedGroupId);
            if (level < 1) throw new IllegalArgumentException("Invalid research reward summary");
            Objects.requireNonNull(choiceGroupIds);
        }
    }

}
