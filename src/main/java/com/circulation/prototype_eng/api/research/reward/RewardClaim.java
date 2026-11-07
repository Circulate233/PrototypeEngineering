package com.circulation.prototype_eng.api.research.reward;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.util.ResourceLocation;

/** Complete immutable save state. Eligibility and selected plans survive forgetting and source consumption. */
public record RewardClaim(Key key, Status status, long eligibleAtWorldTime,
        Optional<ResourceLocation> selectedGroupId, OptionalLong claimingAtWorldTime,
        ObjectList<Child> children, OptionalLong claimedAtWorldTime) {
    public RewardClaim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(selectedGroupId, "selectedGroupId");
        Objects.requireNonNull(claimingAtWorldTime, "claimingAtWorldTime");
        Objects.requireNonNull(claimedAtWorldTime, "claimedAtWorldTime");
        children = ObjectLists.unmodifiable(new ObjectArrayList<>(Objects.requireNonNull(children, "children")));
        boolean selected = status != Status.UNCLAIMED;
        if (eligibleAtWorldTime < 0 || selected != selectedGroupId.isPresent()
                || selected != claimingAtWorldTime.isPresent() || selected == children.isEmpty()
                || (status == Status.CLAIMED) != claimedAtWorldTime.isPresent()
                || (selected && claimingAtWorldTime.getAsLong() < eligibleAtWorldTime)
                || (claimedAtWorldTime.isPresent() && claimedAtWorldTime.getAsLong() < claimingAtWorldTime.getAsLong())) {
            throw new IllegalArgumentException("Invalid reward claim state");
        }
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        var fragments = new ObjectOpenHashSet<UUID>();
        for (var child : children) {
            Objects.requireNonNull(child, "child");
            var parent = child.key();
            if (!ids.add(parent.childId()) || !parent.playerUuid().equals(key.playerUuid())
                    || !parent.researchId().equals(key.researchId()) || parent.level() != key.level()
                    || !parent.groupId().equals(selectedGroupId.orElseThrow())
                    || (status == Status.CLAIMED && child.status() != ChildStatus.COMPLETED)) {
                throw new IllegalArgumentException("Invalid reward child identity/state");
            }
            for (var fragment : child.fragments().values()) {
                if (!fragments.add(fragment.id())) throw new IllegalArgumentException("Duplicate reward fragment ID");
            }
        }
    }

    public static RewardClaim unclaimed(Key key, long time) {
        return new RewardClaim(key, Status.UNCLAIMED, time, Optional.empty(), OptionalLong.empty(),
                ObjectLists.emptyList(), OptionalLong.empty());
    }

    public RewardClaim select(ResourceLocation group, long time, ObjectList<Child> plan) {
        if (status != Status.UNCLAIMED) throw new IllegalStateException("Selection already fixed");
        return new RewardClaim(key, Status.CLAIMING, eligibleAtWorldTime, Optional.of(group),
                OptionalLong.of(time), plan, OptionalLong.empty());
    }

    /** Replaces one child without permitting changes to its identity, payload, plan or terminal state. */
    public RewardClaim replaceChild(int index, Child replacement) {
        if (status != Status.CLAIMING) throw new IllegalStateException("Claim is not in progress");
        var previous = children.get(index);
        if (previous.status() != ChildStatus.PENDING || !previous.key().equals(replacement.key())
                || previous.kind() != replacement.kind()
                || !previous.fragments().keySet().equals(replacement.fragments().keySet())) {
            throw new IllegalStateException("Reward child plan or terminal state changed");
        }
        for (var entry : previous.fragments().object2ObjectEntrySet()) {
            var before = entry.getValue();
            var after = replacement.fragments().get(entry.getKey());
            if (!samePlan(before, after) || (before.entityUuid().isPresent() && !before.entityUuid().equals(after.entityUuid()))
                    || ((before.status() == RewardFragment.Status.PLANNED || before.status() == RewardFragment.Status.RETRY_PLANNED)
                            && after.status() != before.status()
                            && after.status() != RewardFragment.Status.DELIVERING && after.status() != RewardFragment.Status.ERROR)
                    || (before.status() == RewardFragment.Status.DELIVERING && after.status() != RewardFragment.Status.DELIVERING
                            && after.status() != RewardFragment.Status.CONFIRMED
                            && !(after.status() == RewardFragment.Status.RETRY_PLANNED && before.entityUuid().isEmpty()))
                    || ((before.status() == RewardFragment.Status.CONFIRMED || before.status() == RewardFragment.Status.ERROR)
                            && !before.equals(after))) {
                throw new IllegalStateException("Reward fragment plan or terminal state changed");
            }
        }
        var updated = new ObjectArrayList<>(children);
        updated.set(index, replacement);
        return new RewardClaim(key, status, eligibleAtWorldTime, selectedGroupId, claimingAtWorldTime, updated, claimedAtWorldTime);
    }

    public RewardClaim completed(long time) {
        if (status != Status.CLAIMING) throw new IllegalStateException("Claim is not in progress");
        return new RewardClaim(key, Status.CLAIMED, eligibleAtWorldTime, selectedGroupId, claimingAtWorldTime,
                children, OptionalLong.of(time));
    }

    /** Validates writes made through the player's exclusive reward session. */
    public void requireTransitionTo(RewardClaim next) {
        if (!key.equals(next.key) || eligibleAtWorldTime != next.eligibleAtWorldTime) {
            throw new IllegalStateException("Reward eligibility identity changed");
        }
        if (equals(next)) return;
        if (status == Status.UNCLAIMED) {
            if (next.status != Status.CLAIMING) throw new IllegalStateException("Claim must select before delivery");
            return;
        }
        if (status == Status.CLAIMED || !selectedGroupId.equals(next.selectedGroupId)
                || !claimingAtWorldTime.equals(next.claimingAtWorldTime) || children.size() != next.children.size()) {
            throw new IllegalStateException("Reward selection or terminal state changed");
        }
        if (next.status == Status.CLAIMED) {
            if (!children.equals(next.children)) throw new IllegalStateException("Claim completion changed children");
            return;
        }
        if (next.status != Status.CLAIMING) throw new IllegalStateException("Claim state moved backward");
        for (int index = 0; index < children.size(); index++) {
            if (!children.get(index).equals(next.children.get(index))) replaceChild(index, next.children.get(index));
        }
    }

    private static boolean samePlan(RewardFragment first, RewardFragment second) {
        return first.id().equals(second.id()) && first.parent().equals(second.parent())
                && first.resource().equals(second.resource()) && first.count() == second.count()
                && first.taggedStackNbt().equals(second.taggedStackNbt()) && first.target().equals(second.target());
    }

    public record Key(UUID playerUuid, ResourceLocation researchId, int level) {
        public Key {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(researchId, "researchId");
            if (level < 1) throw new IllegalArgumentException("Reward level must be positive");
        }
    }

    public enum Status { UNCLAIMED, CLAIMING, CLAIMED }
    public enum Kind { EVENT, ITEM }
    public enum ChildStatus { PENDING, COMPLETED, ERROR }

    public record Child(RewardEventKey key, Kind kind, ChildStatus status,
            Object2ObjectMap<UUID, RewardFragment> fragments) {
        public Child {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            var copy = new Object2ObjectLinkedOpenHashMap<UUID, RewardFragment>();
            for (var entry : Objects.requireNonNull(fragments, "fragments").object2ObjectEntrySet()) {
                var fragment = Objects.requireNonNull(entry.getValue(), "fragment");
                if (!fragment.id().equals(entry.getKey()) || !fragment.parent().equals(key)
                        || (status == ChildStatus.COMPLETED && fragment.status() != RewardFragment.Status.CONFIRMED)) {
                    throw new IllegalArgumentException("Invalid reward child fragment");
                }
                copy.put(entry.getKey(), fragment);
            }
            if ((kind == Kind.EVENT && !copy.isEmpty()) || (kind == Kind.ITEM && copy.isEmpty())) {
                throw new IllegalArgumentException("Fragment plan does not match child kind");
            }
            fragments = RewardSnapshots.map(copy);
        }

        public Child replaceFragment(RewardFragment fragment) {
            if (!fragments.containsKey(fragment.id())) throw new IllegalArgumentException("Unknown reward fragment");
            var updated = new Object2ObjectLinkedOpenHashMap<>(fragments);
            updated.put(fragment.id(), fragment);
            return new Child(key, kind, status, updated);
        }

        public Child withStatus(ChildStatus next) { return new Child(key, kind, next, fragments); }
    }
}
