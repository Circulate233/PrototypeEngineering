package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.AbstractObject2IntMap;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable PN manual-research recovery checkpoint. Transitions do not consume inventory, propagate
 * completion or write a checkpoint. All times use one persistent server timeline across dimensions.
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
        work = immutableWork(work);
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

    static Object2LongMap<ManualMaterialKey> immutableWork(Object2LongMap<ManualMaterialKey> source) {
        return Object2LongMaps.unmodifiable(new FrozenWork(source));
    }

    private static void requireExpected(Object2LongMap<ManualMaterialKey> work, MaterialIntent intent) {
        if (!addWork(work, intent.pendingWork()).equals(intent.expectedWork())) {
            throw new IllegalArgumentException("Manual expected work differs from accumulated work plus its pending delta");
        }
    }

    private static Object2LongMap<ManualMaterialKey> addWork(Object2LongMap<ManualMaterialKey> before, Object2LongMap<ManualMaterialKey> delta) {
        var result = new Object2LongOpenHashMap<>(before);
        result.defaultReturnValue(0);
        for (var entry : delta.object2LongEntrySet()) {
            if (entry.getLongValue() > Long.MAX_VALUE - result.getLong(entry.getKey())) {
                throw new IllegalArgumentException("Manual work overflows long");
            }
            result.addTo(entry.getKey(), entry.getLongValue());
        }
        return immutableWork(result);
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

        /** Shared declaration order; callers must not modify this array. */
        public static final Status[] VALUES = values();
    }

    public enum PauseReason {
        CONTRIBUTOR_OFFLINE, CONTRIBUTOR_TEAM_CHANGED, SESSION_TIMEOUT, EXPLICIT, WORLD_RESTORED, RESEARCH_INVALIDATED;

        /** Shared declaration order; callers must not modify this array. */
        public static final PauseReason[] VALUES = values();
    }

    public record MaterialIntent(UUID operationId, UUID contributor, long sequence,
                                 Object2LongMap<ManualMaterialKey> pendingWork,
                                 Object2LongMap<ManualMaterialKey> expectedWork) {
        public MaterialIntent {
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(contributor);
            if (sequence < 0) {
                throw new IllegalArgumentException("Negative manual material sequence");
            }
            pendingWork = immutableWork(pendingWork);
            expectedWork = immutableWork(expectedWork);
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

    public record CompletionIntent(long worldTime, Object2IntMap<UUID> baselines) {
        public CompletionIntent {
            if (worldTime < 0) {
                throw new IllegalArgumentException("Negative manual completion time");
            }
            baselines = Object2IntMaps.unmodifiable(new FrozenBaselines(baselines));
            if (baselines.isEmpty()) {
                throw new IllegalArgumentException("Manual completion needs frozen member baselines");
            }
        }
    }

    private static final class FrozenWork extends AbstractObject2LongMap<ManualMaterialKey> {
        private final Object2LongOpenHashMap<ManualMaterialKey> values = new Object2LongOpenHashMap<>();
        private final ObjectSet<Object2LongMap.Entry<ManualMaterialKey>> entries;

        private FrozenWork(Object2LongMap<ManualMaterialKey> source) {
            var copy = new ObjectOpenHashSet<Object2LongMap.Entry<ManualMaterialKey>>();
            for (var entry : Objects.requireNonNull(source).object2LongEntrySet()) {
                var key = Objects.requireNonNull(entry.getKey());
                if (entry.getLongValue() < 0 || values.containsKey(key)) {
                    throw new IllegalArgumentException("Invalid or duplicate manual work key");
                }
                values.put(key, entry.getLongValue());
                copy.add(new BasicEntry<>(key, entry.getLongValue()));
            }
            entries = ObjectSets.unmodifiable(copy);
        }

        @Override
        public ObjectSet<Object2LongMap.Entry<ManualMaterialKey>> object2LongEntrySet() {
            return entries;
        }

        @Override
        public long getLong(Object key) {
            return values.getLong(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public int size() {
            return values.size();
        }
    }

    private static final class FrozenBaselines extends AbstractObject2IntMap<UUID> {
        private final Object2IntOpenHashMap<UUID> values = new Object2IntOpenHashMap<>();
        private final ObjectSet<Object2IntMap.Entry<UUID>> entries;

        private FrozenBaselines(Object2IntMap<UUID> source) {
            values.defaultReturnValue(-1);
            defaultReturnValue(-1);
            var copy = new ObjectOpenHashSet<Object2IntMap.Entry<UUID>>();
            for (var entry : Objects.requireNonNull(source).object2IntEntrySet()) {
                var key = Objects.requireNonNull(entry.getKey());
                if (entry.getIntValue() < 0 || values.containsKey(key)) {
                    throw new IllegalArgumentException("Invalid or duplicate manual member baseline");
                }
                values.put(key, entry.getIntValue());
                copy.add(new BasicEntry<>(key, entry.getIntValue()));
            }
            entries = ObjectSets.unmodifiable(copy);
        }

        @Override
        public ObjectSet<Object2IntMap.Entry<UUID>> object2IntEntrySet() {
            return entries;
        }

        @Override
        public int getInt(Object key) {
            return values.getInt(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public int size() {
            return values.size();
        }
    }
}
