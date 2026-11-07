package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;

/** Shared feedback is synchronized as an enum; translation happens on the receiving client. */
public enum ResearchGuiText implements LocalizationEnum {
    Title, Research, Queue, Rewards, Previous, Next, Page, Start, Takeover, Blacklist, Unblacklist, Claim,
    ContinueClaim, Description, Details, NoEntries, Loading, Ready, Saving, Busy, Stale, Forbidden,
    NoTeam, TeamUnavailable, TeamDenied, TeamAvailable, PersonalLevel, SharedLevel, TargetLevel, Tier,
    PrerequisitesOnly, NotStartable, Blacklisted, MissingContent, ResourceProgress, MaterialProgress,
    RewardChoice, FixedChoice, Contributor, NoContributor, NoDetails, ContentPage, ActionQueued,
    Started, QueueFull, DuplicateTarget, NoCenter, DataCenterTier, StationTier, Changed,
    Claimed, MissingClaim, AlreadyClaimed, UnknownGroup, ChoiceFixed, RetryLater, RecoveryError,
    Resumed, AlreadyPresent, Advanced, Completed, Cancelled, Paused, WaitingForTurn, NoMaterials,
    ContributorUnavailable, RecoveryPending, CompletionPending,
    ManualActive, ManualPaused, MaterialCommitPending, MaterialCommitted, MaterialAbortPending,
    MaterialSettlementPending, CompletionPropagated, QueueHeadRemoved,
    ContributorOffline, ContributorTeamChanged, SessionTimeout, ExplicitPause, WorldRestored, ResearchInvalidated,
    Unclaimed, Claiming, RewardClaimed, Compute, Qubit, Threads;

    private final String translationKey = "gui." + Tags.MOD_ID + ".research." + name();
    @Override public String getTranslationKey() { return translationKey; }

    public static ResearchGuiText start(TeamResearchStart.Status status) {
        return switch (status) {
            case STARTED -> Started;
            case QUEUE_BUSY -> Busy;
            case QUEUE_FULL -> QueueFull;
            case DUPLICATE_TARGET -> DuplicateTarget;
            case PERMISSION_DENIED -> Forbidden;
            case NOT_STARTABLE -> NotStartable;
            case NO_ACTIVE_CENTER -> NoCenter;
            case DATA_CENTER_LEVEL_INSUFFICIENT -> DataCenterTier;
            case STATION_LEVEL_INSUFFICIENT -> StationTier;
            case STALE_CONTEXT -> Stale;
        };
    }

    public static ResearchGuiText reward(ResearchRewardClaimer.Status status) {
        return switch (status) {
            case CLAIMED -> Claimed;
            case MISSING_CLAIM -> MissingClaim;
            case ALREADY_CLAIMED -> AlreadyClaimed;
            case UNKNOWN_GROUP -> UnknownGroup;
            case CHOICE_FIXED -> ChoiceFixed;
            case BUSY -> Busy;
            case RETRY_LATER -> RetryLater;
            case RECOVERY_ERROR -> RecoveryError;
            case STALE_CONTEXT -> Stale;
        };
    }

    public static ResearchGuiText manual(ManualResearch.Status status) {
        return switch (status) {
            case STARTED -> Started;
            case ALREADY_PRESENT -> AlreadyPresent;
            case RESUMED -> Resumed;
            case ADVANCED -> Advanced;
            case COMPLETED -> Completed;
            case CANCELLED -> Cancelled;
            case PAUSED -> Paused;
            case WAITING_FOR_TURN -> WaitingForTurn;
            case NO_MATERIALS -> NoMaterials;
            case CONTRIBUTOR_UNAVAILABLE -> ContributorUnavailable;
            case STALE_CONTEXT -> Stale;
            case RECOVERY_PENDING -> RecoveryPending;
            case COMPLETION_PENDING -> CompletionPending;
        };
    }

    public static ResearchGuiText session(ManualResearchSession.Status status) {
        return switch (status) {
            case ACTIVE -> ManualActive;
            case PAUSED -> ManualPaused;
            case MATERIAL_COMMIT_PENDING -> MaterialCommitPending;
            case MATERIAL_COMMITTED -> MaterialCommitted;
            case MATERIAL_ABORT_RECEIPT_PENDING -> MaterialAbortPending;
            case MATERIAL_SETTLED_RECEIPT_PENDING -> MaterialSettlementPending;
            case COMPLETION_PENDING -> CompletionPending;
            case COMPLETION_PROPAGATED -> CompletionPropagated;
            case QUEUE_HEAD_REMOVED -> QueueHeadRemoved;
        };
    }

    public static ResearchGuiText pause(ManualResearchSession.PauseReason reason) {
        return switch (reason) {
            case CONTRIBUTOR_OFFLINE -> ContributorOffline;
            case CONTRIBUTOR_TEAM_CHANGED -> ContributorTeamChanged;
            case SESSION_TIMEOUT -> SessionTimeout;
            case EXPLICIT -> ExplicitPause;
            case WORLD_RESTORED -> WorldRestored;
            case RESEARCH_INVALIDATED -> ResearchInvalidated;
        };
    }

    public static ResearchGuiText team(ResearchPlayerView.TeamStatus status) {
        return switch (status) {
            case AVAILABLE -> TeamAvailable;
            case MISSING_TEAM -> NoTeam;
            case UNAVAILABLE -> TeamUnavailable;
            case DENIED -> TeamDenied;
        };
    }

    public static ResearchGuiText claim(RewardClaim.Status status) {
        return switch (status) {
            case UNCLAIMED -> Unclaimed;
            case CLAIMING -> Claiming;
            case CLAIMED -> RewardClaimed;
        };
    }

    public static ResearchGuiText permission(ResearchPermissionPolicy.Decision decision) {
        return decision == ResearchPermissionPolicy.Decision.ALLOWED ? Changed : Forbidden;
    }
}
