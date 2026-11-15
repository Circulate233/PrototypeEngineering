package com.circulation.prototype_eng.api.research.reward;

import org.jetbrains.annotations.Nullable;

import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Complete immutable save state. Eligibility and selected plans survive forgetting and source consumption.
 * The children list is borrowed immutable data: callers and consumers must leave it unchanged for
 * the lifetime of this claim and all checkpoints that retain it. Transitions create new lists.
 * The selected group is null before selection. Timestamps use -1 until the corresponding transition occurs.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record RewardClaim(Key key, Status status, long eligibleAtWorldTime,
                          @Nullable ResourceLocation selectedGroupId, long claimingAtWorldTime,
                          ObjectList<Child> children, long claimedAtWorldTime) {
    public static RewardClaim unclaimed(Key key, long time) {
        return new RewardClaim(key, Status.UNCLAIMED, time, null, -1,
            ObjectLists.emptyList(), -1);
    }

    public RewardClaim select(ResourceLocation group, long time, ObjectList<Child> plan) {
        if (status != Status.UNCLAIMED) throw new IllegalStateException("Selection already fixed");
        return new RewardClaim(key, Status.CLAIMING, eligibleAtWorldTime, group,
            time, plan, -1);
    }

    /**
     * Replaces one child without permitting changes to its identity, payload, plan or terminal state.
     */
    public RewardClaim replaceChild(int index, Child replacement) {
        if (status != Status.CLAIMING) throw new IllegalStateException("Claim is not in progress");
        validateChildReplacement(children.get(index), replacement);
        var updated = new ObjectArrayList<>(children);
        updated.set(index, replacement);
        return new RewardClaim(key, status, eligibleAtWorldTime, selectedGroupId, claimingAtWorldTime, updated, claimedAtWorldTime);
    }

    private static void validateChildReplacement(Child previous, Child replacement) {
        if (previous.status() != ChildStatus.PENDING || !previous.key().equals(replacement.key())
            || previous.kind() != replacement.kind()
            || !previous.fragments().keySet().equals(replacement.fragments().keySet())) {
            throw new IllegalStateException("Reward child plan or terminal state changed");
        }
        for (var entry : previous.fragments().object2ObjectEntrySet()) {
            var before = entry.getValue();
            var after = replacement.fragments().get(entry.getKey());
            before.requireTransitionTo(after);
        }
    }

    public RewardClaim completed(long time) {
        if (status != Status.CLAIMING) throw new IllegalStateException("Claim is not in progress");
        return new RewardClaim(key, Status.CLAIMED, eligibleAtWorldTime, selectedGroupId, claimingAtWorldTime,
            children, time);
    }

    /**
     * Validates writes made through the player's exclusive reward session.
     */
    public void requireTransitionTo(RewardClaim next) {
        if (!key.equals(next.key) || eligibleAtWorldTime != next.eligibleAtWorldTime) {
            throw new IllegalStateException("Reward eligibility identity changed");
        }
        if (equals(next)) return;
        if (status == Status.UNCLAIMED) {
            if (next.status != Status.CLAIMING) throw new IllegalStateException("Claim must select before delivery");
            return;
        }
        if (status == Status.CLAIMED || !Objects.equals(selectedGroupId, next.selectedGroupId)
            || claimingAtWorldTime != next.claimingAtWorldTime || children.size() != next.children.size()) {
            throw new IllegalStateException("Reward selection or terminal state changed");
        }
        if (next.status == Status.CLAIMED) {
            if (!children.equals(next.children)) throw new IllegalStateException("Claim completion changed children");
            return;
        }
        if (next.status != Status.CLAIMING) throw new IllegalStateException("Claim state moved backward");
        for (int index = 0; index < children.size(); index++) {
            if (!children.get(index).equals(next.children.get(index)))
                validateChildReplacement(children.get(index), next.children.get(index));
        }
    }

    public enum Status {
        UNCLAIMED, CLAIMING, CLAIMED;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final Status[] VALUES = values();
    }

    public enum Kind {
        EVENT, ITEM;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final Kind[] VALUES = values();
    }

    public enum ChildStatus {
        PENDING, COMPLETED, ERROR;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final ChildStatus[] VALUES = values();
    }

    public record Key(UUID playerUuid, ResourceLocation researchId, int level) {
        public Key {
            if (level < 1) throw new IllegalArgumentException("Reward level must be positive");
        }
    }

    /**
     * Fragments are a completed ordered map borrowed read-only throughout this child's lifetime.
     */
    public record Child(RewardEventKey key, Kind kind, ChildStatus status,
                        Object2ObjectMap<UUID, ItemDeliveryFragment> fragments) {
        public Child replaceFragment(ItemDeliveryFragment fragment) {
            if (!fragments.containsKey(fragment.id())) throw new IllegalArgumentException("Unknown reward fragment");
            var updated = new Object2ObjectLinkedOpenHashMap<>(fragments);
            updated.put(fragment.id(), fragment);
            return new Child(key, kind, status, updated);
        }

        public Child withStatus(ChildStatus next) {
            return new Child(key, kind, next, fragments);
        }
    }
}
