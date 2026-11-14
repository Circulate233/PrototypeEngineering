package com.circulation.prototype_eng.api.research.view;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One player's detached display data; it contains no execution plans or recovery receipts.
 * Collections are completed immutable data borrowed for the view's lifetime. Maps use zero default
 * values; producers must omit data denied by the captured permissions and callers must not modify the collections.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record ResearchPlayerView(UUID playerUuid, long serverTime, boolean savePending,
                                 Decision personalPermission, Object2IntMap<ResourceLocation> personalLevels,
                                 Team team, ObjectList<ClaimSummary> claims, Decision forgetPermission,
                                 Decision adminPermission) {


    /**
     * Defaults operation permissions to unavailable.
     */
    @SuppressWarnings("unused") // Public view constructor with default operation permissions.
    public ResearchPlayerView(UUID playerUuid, long serverTime, boolean savePending,
                              Decision personalPermission, Object2IntMap<ResourceLocation> personalLevels,
                              Team team, ObjectList<ClaimSummary> claims) {
        this(playerUuid, serverTime, savePending, personalPermission, personalLevels, team, claims,
            Decision.TARGET_PLAYER_MISMATCH, Decision.NOT_SERVER_OPERATOR);
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
                       Reference2DoubleMap<NetworkResourceType> numericProgress, ObjectList<ManualRow> manualRows) {

    }

    public record QueueRow(long entryId, ResourceLocation researchId, int targetLevel,
                           UUID requestedBy, long createdAtWorldTime) {

    }

    /**
     * Paid material counts are displayable; pending deltas and player inventory receipts are omitted.
     */
    public record ManualRow(long entryId, ManualResearchSession.Status status,
                            @Nullable ManualResearchSession.PauseReason pauseReason, @Nullable UUID contributor,
                            Object2LongMap<ManualMaterialKey> work) {

    }

    public record ClaimSummary(ResourceLocation researchId, int level, RewardClaim.Status status,
                               @Nullable ResourceLocation selectedGroupId,
                               ObjectList<ResourceLocation> choiceGroupIds) {

    }

}
