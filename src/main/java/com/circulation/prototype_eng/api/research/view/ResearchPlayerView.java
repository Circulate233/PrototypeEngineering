package com.circulation.prototype_eng.api.research.view;

import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.ResearchSnapshots;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** One player's detached display data; it contains no execution plans or recovery receipts. */
public record ResearchPlayerView(UUID playerUuid, long serverTime, boolean savePending,
                                 Decision personalPermission, Object2IntMap<ResourceLocation> personalLevels,
                                 Team team, ObjectList<ClaimSummary> claims, Decision forgetPermission, Decision adminPermission) {
    public ResearchPlayerView {
        Objects.requireNonNull(playerUuid);
        if (serverTime < 0) throw new IllegalArgumentException("Negative research view time");
        Objects.requireNonNull(personalPermission);
        Objects.requireNonNull(forgetPermission);
        Objects.requireNonNull(adminPermission);
        personalLevels = levels(personalLevels);
        Objects.requireNonNull(team);
        claims = list(claims);
        if (personalPermission != Decision.ALLOWED && (!personalLevels.isEmpty() || !claims.isEmpty())) {
            throw new IllegalArgumentException("Denied personal view contains private data");
        }
    }

    /** Compatibility constructor: an older detached view grants no new operation permissions. */
    public ResearchPlayerView(UUID playerUuid, long serverTime, boolean savePending,
                              Decision personalPermission, Object2IntMap<ResourceLocation> personalLevels,
                              Team team, ObjectList<ClaimSummary> claims) {
        this(playerUuid, serverTime, savePending, personalPermission, personalLevels, team, claims,
            Decision.TARGET_PLAYER_MISMATCH, Decision.NOT_SERVER_OPERATOR);
    }

    public enum TeamStatus { AVAILABLE, MISSING_TEAM, UNAVAILABLE, DENIED }

    /** Structural targets describe prerequisites only; admission still evaluates the real actor. */
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
            if (!id.equals(id.trim()) || queueEpoch < 0) throw new IllegalArgumentException("Invalid research team view");
            sharedLevels = levels(sharedLevels);
            structuralTargets = levels(structuralTargets);
            blacklist = set(blacklist);
            queueRows = list(queueRows);
            numericProgress = counts(numericProgress);
            manualRows = list(manualRows);
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
            if (entryId < 1 || targetLevel < 1 || createdAtWorldTime < 0) throw new IllegalArgumentException("Invalid research queue row");
        }
    }

    /** Paid material counts are displayable; pending deltas and player inventory receipts are omitted. */
    public record ManualRow(long entryId, ManualResearchSession.Status status,
                            @Nullable ManualResearchSession.PauseReason pauseReason, @Nullable UUID contributor,
                            Object2LongMap<ManualMaterialKey> work) {
        public ManualRow {
            if (entryId < 1) throw new IllegalArgumentException("Invalid manual research row");
            Objects.requireNonNull(status);
            work = counts(work);
        }
    }

    public record ClaimSummary(ResourceLocation researchId, int level, RewardClaim.Status status,
                               Optional<ResourceLocation> selectedGroupId, ObjectList<ResourceLocation> choiceGroupIds) {
        public ClaimSummary {
            Objects.requireNonNull(researchId);
            Objects.requireNonNull(status);
            Objects.requireNonNull(selectedGroupId);
            if (level < 1) throw new IllegalArgumentException("Invalid research reward summary");
            choiceGroupIds = list(choiceGroupIds);
        }
    }

    private static Object2IntMap<ResourceLocation> levels(Object2IntMap<ResourceLocation> source) {
        for (var entry : Objects.requireNonNull(source).object2IntEntrySet()) {
            Objects.requireNonNull(entry.getKey());
            if (entry.getIntValue() < 0) throw new IllegalArgumentException("Negative research view level");
        }
        return ResearchSnapshots.levels(source);
    }

    private static <T> ObjectList<T> list(ObjectList<T> source) {
        var copy = new ObjectArrayList<>(Objects.requireNonNull(source));
        for (var value : copy) Objects.requireNonNull(value);
        return ObjectLists.unmodifiable(copy);
    }

    private static <T> ObjectSet<T> set(ObjectSet<T> source) {
        var copy = new ObjectOpenHashSet<>(Objects.requireNonNull(source));
        for (var value : copy) Objects.requireNonNull(value);
        return ObjectSets.unmodifiable(copy);
    }

    private static <T> Object2LongMap<T> counts(Object2LongMap<T> source) {
        return Object2LongMaps.unmodifiable(new FrozenCounts<>(source));
    }

    /** Detached entries prevent entry.setValue from changing an allegedly immutable wire snapshot. */
    private static final class FrozenCounts<T> extends AbstractObject2LongMap<T> {
        private final Object2LongOpenHashMap<T> values = new Object2LongOpenHashMap<>();
        private final ObjectSet<Object2LongMap.Entry<T>> entries;

        private FrozenCounts(Object2LongMap<T> source) {
            var snapshot = new ObjectOpenHashSet<Object2LongMap.Entry<T>>();
            for (var entry : Objects.requireNonNull(source).object2LongEntrySet()) {
                Objects.requireNonNull(entry.getKey());
                if (entry.getLongValue() < 0) throw new IllegalArgumentException("Negative research view progress");
                values.put(entry.getKey(), entry.getLongValue());
                snapshot.add(new BasicEntry<>(entry.getKey(), entry.getLongValue()));
            }
            entries = ObjectSets.unmodifiable(snapshot);
        }

        @Override public ObjectSet<Object2LongMap.Entry<T>> object2LongEntrySet() { return entries; }
        @Override public long getLong(Object key) { return values.getLong(key); }
        @Override public boolean containsKey(Object key) { return values.containsKey(key); }
        @Override public int size() { return values.size(); }
    }
}
