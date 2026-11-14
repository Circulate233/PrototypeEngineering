package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable PN manual-research recovery checkpoint. Transitions do not consume inventory, propagate
 * completion or write a checkpoint. All times use one persistent server timeline across dimensions.
 * Work maps are completed, zero-default maps borrowed read-only for the checkpoint lifetime.
 * Producers and consumers must not mutate them; transitions allocate only when quantities change.
 */
@SuppressWarnings("unused")
public record ManualResearchSession(UUID id, String teamId, TeamResearchQueue.Key queueKey,
                                    @Nullable UUID contributor, long lastActivity, Status status,
                                    @Nullable PauseReason pauseReason, Object2LongMap<ManualMaterialKey> work,
                                    @Nullable MaterialIntent materialIntent,
                                    @Nullable CompletionIntent completionIntent) {
    public ManualResearchSession {
        Objects.requireNonNull(id);
        Objects.requireNonNull(teamId);
        Objects.requireNonNull(queueKey);
        Objects.requireNonNull(status);
        if (teamId.isBlank() || !teamId.equals(teamId.trim()) || lastActivity < 0) {
            throw new IllegalArgumentException("Invalid manual session team or activity time");
        }
        validateWork(work);
        if (completionIntent != null && completionIntent.worldTime() > lastActivity) {
            throw new IllegalArgumentException("Manual completion time exceeds last activity");
        }
        switch (status) {
            case ACTIVE -> {
                if (contributor == null || pauseReason != null || materialIntent != null || completionIntent != null) {
                    throw new IllegalArgumentException("Active manual session requires only its active contributor");
                }
            }
            case PAUSED -> {
                if (contributor != null || pauseReason == null || materialIntent != null || completionIntent != null) {
                    throw new IllegalArgumentException("Paused manual session requires only a pause reason");
                }
            }
            case MATERIAL_COMMIT_PENDING, MATERIAL_COMMITTED -> {
                if (contributor != null || pauseReason != null || materialIntent == null) {
                    throw new IllegalArgumentException("Unsettled manual material state requires a complete intent");
                }
                requireExpected(work, materialIntent);
            }
            case MATERIAL_ABORT_RECEIPT_PENDING -> {
                if (contributor != null || !isAbortReason(pauseReason) || materialIntent == null
                    || completionIntent != null) {
                    throw new IllegalArgumentException("Manual abort receipt requires an uncommitted intent and invalidation reason");
                }
                requireExpected(work, materialIntent);
            }
            case MATERIAL_SETTLED_RECEIPT_PENDING -> {
                if (contributor != null || pauseReason != null || materialIntent == null || materialIntent.expectedWork().isEmpty()
                    || !materialIntent.expectedWork().equals(work)) {
                    throw new IllegalArgumentException("Settled manual material state requires expected work equal to accumulated work");
                }
                for (var entry : materialIntent.pendingWork().object2LongEntrySet()) {
                    if (work.getLong(entry.getKey()) < entry.getLongValue()) {
                        throw new IllegalArgumentException("Settled manual work is smaller than its pending delta");
                    }
                }
            }
            case COMPLETION_PENDING, COMPLETION_PROPAGATED, QUEUE_HEAD_REMOVED -> {
                if (contributor != null || pauseReason != null || materialIntent != null || completionIntent == null) {
                    throw new IllegalArgumentException("Manual completion state requires only frozen completion metadata");
                }
            }
        }
    }

    public static ManualResearchSession newActive(UUID id, String teamId, TeamResearchQueue.Key key, UUID contributor, long time) {
        return new ManualResearchSession(id, teamId, key, Objects.requireNonNull(contributor), time, Status.ACTIVE,
            null, Object2LongMaps.emptyMap(), null, null);
    }

    private static boolean isAbortReason(@Nullable PauseReason reason) {
        return reason == PauseReason.CONTRIBUTOR_TEAM_CHANGED || reason == PauseReason.RESEARCH_INVALIDATED;
    }

    static void validateWork(Object2LongMap<ManualMaterialKey> source) {
        for (var entry : Objects.requireNonNull(source).object2LongEntrySet()) {
            Objects.requireNonNull(entry.getKey());
            if (entry.getLongValue() < 0) throw new IllegalArgumentException("Negative manual work amount");
        }
    }

    private static void requireExpected(Object2LongMap<ManualMaterialKey> work, MaterialIntent intent) {
        var delta = intent.pendingWork();
        var expected = intent.expectedWork();
        long keys = work.size();
        for (var entry : work.object2LongEntrySet()) {
            var key = entry.getKey();
            long addition = delta.getOrDefault(key, 0L);
            if (addition > Long.MAX_VALUE - entry.getLongValue())
                throw new IllegalArgumentException("Manual work overflows long");
            if (!expected.containsKey(key) || expected.getLong(key) != entry.getLongValue() + addition)
                throw new IllegalArgumentException("Manual expected work differs from accumulated work plus its pending delta");
        }
        for (var entry : delta.object2LongEntrySet()) {
            if (work.containsKey(entry.getKey())) continue;
            keys++;
            if (!expected.containsKey(entry.getKey()) || expected.getLong(entry.getKey()) != entry.getLongValue())
                throw new IllegalArgumentException("Manual expected work differs from accumulated work plus its pending delta");
        }
        if (keys != expected.size()) throw new IllegalArgumentException("Manual expected work has unrelated entries");
    }

    /**
     * Loading never silently resumes an active inventory contributor. Pending recovery states stay exact.
     */
    public ManualResearchSession restored() {
        return status == Status.ACTIVE ? copy(null, lastActivity, Status.PAUSED, PauseReason.WORLD_RESTORED, work, null, null) : this;
    }

    public ManualResearchSession resume(UUID player, long time) {
        if (status != Status.ACTIVE && status != Status.PAUSED) {
            throw new IllegalStateException("Manual receipt recovery must finish before resuming");
        }
        requireTime(time);
        return copy(Objects.requireNonNull(player), time, Status.ACTIVE, null, work, null, null);
    }

    public ManualResearchSession withWork(Object2LongMap<ManualMaterialKey> completed) {
        requireStatus(Status.ACTIVE);
        return copy(contributor, lastActivity, status, null, completed, null, null);
    }

    public ManualResearchSession pause(PauseReason reason, long time) {
        requireStatus(Status.ACTIVE);
        requireTime(time);
        return copy(null, time, Status.PAUSED, Objects.requireNonNull(reason), work, null, null);
    }

    public ManualResearchSession beginMaterial(MaterialIntent intent, long time, @Nullable CompletionIntent completion) {
        requireStatus(Status.ACTIVE);
        requireTime(time);
        Objects.requireNonNull(intent);
        if (!intent.contributor().equals(contributor)
            || completion != null && completion.worldTime() != time) {
            throw new IllegalArgumentException("Manual material intent differs from the active contributor or final checkpoint time");
        }
        return copy(null, time, Status.MATERIAL_COMMIT_PENDING, null, work, intent, completion);
    }

    public ManualResearchSession markMaterialCommitted(UUID operationId) {
        requireStatus(Status.MATERIAL_COMMIT_PENDING);
        requireOperation(operationId);
        return copy(null, lastActivity, Status.MATERIAL_COMMITTED, null, work, materialIntent, completionIntent);
    }

    public ManualResearchSession settleMaterial(UUID operationId, long time) {
        requireStatus(Status.MATERIAL_COMMITTED);
        var intent = requireOperation(operationId);
        requireTime(time);
        return copy(null, time, Status.MATERIAL_SETTLED_RECEIPT_PENDING, null, intent.expectedWork(), intent, completionIntent);
    }

    public ManualResearchSession finishMaterialReceipt(UUID operationId, long time) {
        requireStatus(Status.MATERIAL_SETTLED_RECEIPT_PENDING);
        var intent = requireOperation(operationId);
        requireTime(time);
        return completionIntent == null
            ? copy(intent.contributor(), time, Status.ACTIVE, null, work, null, null)
            : copy(null, time, Status.COMPLETION_PENDING, null, work, null, completionIntent);
    }

    public ManualResearchSession abortPrepared(UUID operationId, PauseReason reason, long time) {
        requireStatus(Status.MATERIAL_COMMIT_PENDING);
        requireOperation(operationId);
        requireTime(time);
        if (!isAbortReason(reason)) {
            throw new IllegalArgumentException("Prepared manual abort requires contributor team change or research invalidation");
        }
        return copy(null, time, Status.MATERIAL_ABORT_RECEIPT_PENDING, reason, work, materialIntent, null);
    }

    public ManualResearchSession finishAbort(UUID operationId, long time) {
        requireStatus(Status.MATERIAL_ABORT_RECEIPT_PENDING);
        requireOperation(operationId);
        requireTime(time);
        return copy(null, time, Status.PAUSED, pauseReason, work, null, null);
    }

    public ManualResearchSession beginCompletion(CompletionIntent completion) {
        requireStatus(Status.ACTIVE);
        requireTime(Objects.requireNonNull(completion).worldTime());
        return copy(null, completion.worldTime(), Status.COMPLETION_PENDING, null, work, null, completion);
    }

    public ManualResearchSession markCompletionPropagated() {
        requireStatus(Status.COMPLETION_PENDING);
        return copy(null, lastActivity, Status.COMPLETION_PROPAGATED, null, work, null, completionIntent);
    }

    public ManualResearchSession markQueueHeadRemoved() {
        requireStatus(Status.COMPLETION_PROPAGATED);
        return copy(null, lastActivity, Status.QUEUE_HEAD_REMOVED, null, work, null, completionIntent);
    }

    private ManualResearchSession copy(@Nullable UUID player, long time, Status next, @Nullable PauseReason reason,
                                       Object2LongMap<ManualMaterialKey> updated, @Nullable MaterialIntent material,
                                       @Nullable CompletionIntent completion) {
        return new ManualResearchSession(id, teamId, queueKey, player, time, next, reason, updated, material, completion);
    }

    private void requireTime(long time) {
        if (time < lastActivity) {
            throw new IllegalArgumentException("Manual activity time moved backwards");
        }
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Manual session expected " + expected + ", found " + status);
        }
    }

    private MaterialIntent requireOperation(UUID id) {
        var intent = materialIntent;
        if (intent == null || !intent.operationId().equals(Objects.requireNonNull(id))) {
            throw new IllegalArgumentException("Manual material operation does not match the persisted intent");
        }
        return intent;
    }

    public enum Status {
        ACTIVE, PAUSED, MATERIAL_COMMIT_PENDING, MATERIAL_COMMITTED, MATERIAL_ABORT_RECEIPT_PENDING,
        MATERIAL_SETTLED_RECEIPT_PENDING, COMPLETION_PENDING, COMPLETION_PROPAGATED, QUEUE_HEAD_REMOVED;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final Status[] VALUES = values();
    }

    public enum PauseReason {
        CONTRIBUTOR_OFFLINE, CONTRIBUTOR_TEAM_CHANGED, SESSION_TIMEOUT, EXPLICIT, WORLD_RESTORED, RESEARCH_INVALIDATED;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final PauseReason[] VALUES = values();
    }

    public record MaterialIntent(UUID operationId, UUID contributor, long sequence,
                                 Object2LongMap<ManualMaterialKey> pendingWork,
                                 Object2LongMap<ManualMaterialKey> expectedWork, long elapsedTicks, long advancedAt) {
        public MaterialIntent {
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(contributor);
            if (sequence < 0 || elapsedTicks < 0 || advancedAt < 0) {
                throw new IllegalArgumentException("Negative manual material sequence");
            }
            validateWork(pendingWork);
            validateWork(expectedWork);
            if (pendingWork.isEmpty() || expectedWork.isEmpty()) {
                throw new IllegalArgumentException("Empty manual material intent");
            }
            for (var entry : pendingWork.object2LongEntrySet()) {
                if (entry.getLongValue() == 0 || expectedWork.getLong(entry.getKey()) < entry.getLongValue()) {
                    throw new IllegalArgumentException("Manual pending work must be positive and covered by expected work");
                }
            }
        }
    }

    /**
     * Captures one member-baseline map; the resulting map and its entries are read-only.
     */
    public record CompletionIntent(long worldTime, Object2IntMap<UUID> baselines) {
        public CompletionIntent {
            if (worldTime < 0) {
                throw new IllegalArgumentException("Negative manual completion time");
            }
            var captured = new Object2IntOpenHashMap<UUID>(baselines.size());
            captured.defaultReturnValue(-1);
            for (var entry : baselines.object2IntEntrySet()) {
                Objects.requireNonNull(entry.getKey());
                if (entry.getIntValue() < 0) throw new IllegalArgumentException("Negative manual member baseline");
                captured.put(entry.getKey(), entry.getIntValue());
            }
            baselines = captured;
            if (baselines.isEmpty()) {
                throw new IllegalArgumentException("Manual completion needs frozen member baselines");
            }
        }
    }

}
