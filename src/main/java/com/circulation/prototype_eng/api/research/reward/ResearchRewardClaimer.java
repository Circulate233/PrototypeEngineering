package com.circulation.prototype_eng.api.research.reward;

import org.jetbrains.annotations.Nullable;

import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.delivery.ItemDelivery;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryAdapter;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Executable selection, ordered delivery and crash recovery. Research completion never calls this
 * automatically. External exceptions propagate unchanged after releasing the player session;
 * already written selection/child/fragment state remains available for an explicit recovery attempt.
 */
public final class ResearchRewardClaimer {
    private ResearchRewardClaimer() {
    }

    private static ObjectList<RewardClaim.Child> createChildren(Request request, RewardGroup group) {
        var children = new ObjectArrayList<RewardClaim.Child>(group.children().size());
        var fragmentIds = new ObjectOpenHashSet<UUID>(group.children().size());
        var elements4 = group.children();
        for (int index4 = 0; index4 < elements4.size(); index4++) {
            var definition = elements4.get(index4);
            if (!request.current().getAsBoolean()) return null;
            var key = new RewardEventKey(request.playerState().playerUuid(), request.definition().id(), request.level(), group.id(), definition.id());
            var fragments = new Object2ObjectLinkedOpenHashMap<UUID, ItemDeliveryFragment>();
            var kind = RewardClaim.Kind.EVENT;
            if (definition instanceof RewardChild.Item item) {
                kind = RewardClaim.Kind.ITEM;
                var plan = request.itemDelivery().plan(key, item.resource(), item.amount());
                if (plan == null || plan.isEmpty()) return null;
                long remaining = item.amount();
                for (int index3 = 0; index3 < plan.size(); index3++) {
                    var fragment = plan.get(index3);
                    if (fragment == null || !fragment.parent().equals(key) || !fragment.resource().equals(item.resource())
                        || fragment.status() != ItemDeliveryFragment.Status.PLANNED || (fragment.entityUuid() != null)
                        || fragment.count() > remaining || !fragmentIds.add(fragment.id())) return null;
                    remaining -= fragment.count();
                    fragments.put(fragment.id(), fragment);
                }
                if (remaining != 0) return null;
            }
            children.add(new RewardClaim.Child(key, kind, RewardClaim.ChildStatus.PENDING, fragments));
        }
        return children;
    }

    /**
     * Checks a saved selected plan against the current frozen definition without performing effects.
     */
    public static boolean validRecovery(RewardClaim claim, RewardGroup group) {
        if (claim.children().size() != group.children().size()) return false;
        boolean pending = false;
        for (int index = 0; index < group.children().size(); index++) {
            var definition = group.children().get(index);
            var saved = claim.children().get(index);
            if (!saved.key().childId().equals(definition.id())) return false;
            if (saved.status() != RewardClaim.ChildStatus.COMPLETED) pending = true;
            else if (pending) return false;
            if (definition instanceof RewardChild.Event) {
                if (saved.kind() != RewardClaim.Kind.EVENT || !saved.fragments().isEmpty()) return false;
            } else if (definition instanceof RewardChild.Item item) {
                if (saved.kind() != RewardClaim.Kind.ITEM || saved.fragments().isEmpty()) return false;
                long remaining = item.amount();
                for (var fragment : saved.fragments().values()) {
                    if (!fragment.resource().equals(item.resource()) || fragment.count() > remaining) return false;
                    remaining -= fragment.count();
                }
                if (remaining != 0) return false;
            }
        }
        return true;
    }

    private static Result deliverEvent(Request request, PlayerResearchState.RewardSession session, int index, RewardChild.Event event) {
        var claim = session.requireCurrent();
        var child = claim.children().get(index);
        var delivered = event.action().deliver(child.key(), new ResearchRewardContext(request.playerState().playerUuid(), request.definition(), request.level(), request.currentWorldTime()));
        if (delivered == null) {
            save(request, session, claim.replaceChild(index, child.withStatus(RewardClaim.ChildStatus.ERROR)));
            return result(Status.RECOVERY_ERROR, "Reward event returned no result");
        }
        if (delivered == RewardEventAction.Result.RETRY_LATER)
            return result(Status.RETRY_LATER, "Reward event requested retry");
        save(request, session, claim.replaceChild(index, child.withStatus(RewardClaim.ChildStatus.COMPLETED)));
        return null;
    }

    private static Result deliverItems(Request request, PlayerResearchState.RewardSession session, int index) {
        var status = ItemDelivery.advance(
            () -> session.requireCurrent().children().get(index).fragments().values(),
            fragment -> {
                var claim = session.requireCurrent();
                save(request, session, claim.replaceChild(index, claim.children().get(index).replaceFragment(fragment)));
            }, request.itemDelivery(), request.current());
        var pause = switch (status) {
            case COMPLETED -> null;
            case RETRY_LATER -> Status.RETRY_LATER;
            case RECOVERY_ERROR -> Status.RECOVERY_ERROR;
            case STALE_CONTEXT -> Status.STALE_CONTEXT;
        };
        if (pause != null) return result(pause, status.name());
        var claim = session.requireCurrent();
        save(request, session, claim.replaceChild(index, claim.children().get(index).withStatus(RewardClaim.ChildStatus.COMPLETED)));
        return null;
    }

    private static void save(Request request, PlayerResearchState.RewardSession session, RewardClaim updated) {
        session.replace(updated);
        request.checkpoint().persist(updated);
    }

    private static Result result(Status status, String message) {
        return new Result(status, message, null);
    }

    private static Result stale() {
        return result(Status.STALE_CONTEXT, "Reward actor is no longer current");
    }

    public static Result claim(Request request) {
        Objects.requireNonNull(request, "request");
        var key = new RewardClaim.Key(request.playerState().playerUuid(), request.definition().id(), request.level());
        var session = request.playerState().tryRewardSession(key);
        if (session == null) return result(Status.BUSY, "Player research state has an active writer");
        try (session) {
            return claim(request, session);
        }
    }

    /**
     * Executes under the caller's exact session; the caller retains ownership through its final checkpoint.
     */
    public static Result claim(Request request, PlayerResearchState.RewardSession session) {
        Objects.requireNonNull(request, "request");
        var key = new RewardClaim.Key(request.playerState().playerUuid(), request.definition().id(), request.level());
        if (!Objects.requireNonNull(session).owns(request.playerState(), key)) {
            throw new IllegalArgumentException("Reward request does not own this player/claim session");
        }
        if (!request.current().getAsBoolean()) return stale();
        var existing = session.current();
        if (existing == null) return result(Status.MISSING_CLAIM, "No reward eligibility for this level");
        RewardGroup selected = null;
        var elements = request.definition().rewardGroupsForLevel(request.level());
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var group = elements.get(index1);
            if (group.id().equals(request.selectedGroupId())) selected = group;
        }
        if (selected == null) return result(Status.UNKNOWN_GROUP, "Unknown reward group");
        if (existing.status() != RewardClaim.Status.UNCLAIMED
            && !selected.id().equals(existing.selectedGroupId())) {
            return result(Status.CHOICE_FIXED, "Reward selection is already fixed");
        }
        if (existing.status() == RewardClaim.Status.UNCLAIMED) {
            var plan = createChildren(request, selected);
            if (!request.current().getAsBoolean()) return stale();
            if (plan == null) return result(Status.RECOVERY_ERROR, "Invalid complete reward fragment plan");
            existing = existing.select(selected.id(), request.currentWorldTime(), plan);
            session.replace(existing);
        }
        if (!validRecovery(existing, selected))
            return result(Status.RECOVERY_ERROR, "Saved reward plan differs from its definition");
        // Also retries a failed final checkpoint before treating the in-memory terminal state as durable.
        request.checkpoint().persist(existing);
        var elements2 = existing.children();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var child = elements2.get(index2);
            for (var fragment : child.fragments().values()) {
                if (!request.current().getAsBoolean()) return stale();
                if (child.status() == RewardClaim.ChildStatus.COMPLETED && fragment.status() == ItemDeliveryFragment.Status.CONFIRMED)
                    request.itemDelivery().release(fragment);
            }
        }
        if (existing.status() == RewardClaim.Status.CLAIMED)
            return result(Status.ALREADY_CLAIMED, "Reward already claimed");
        for (int index = 0; index < selected.children().size(); index++) {
            if (!request.current().getAsBoolean()) return stale();
            var child = session.requireCurrent().children().get(index);
            if (child.status() == RewardClaim.ChildStatus.ERROR)
                return result(Status.RECOVERY_ERROR, "Reward child is in ERROR state");
            if (child.status() == RewardClaim.ChildStatus.COMPLETED) continue;
            var definition = selected.children().get(index);
            var pause = switch (definition) {
                case RewardChild.Event event -> deliverEvent(request, session, index, event);
                case RewardChild.Item ignored -> deliverItems(request, session, index);
            };
            if (pause != null) return pause;
        }
        var completed = session.requireCurrent().completed(request.currentWorldTime());
        save(request, session, completed);
        return new Result(Status.CLAIMED, "Reward claimed", completed);
    }

    public enum Status {CLAIMED, MISSING_CLAIM, ALREADY_CLAIMED, UNKNOWN_GROUP, CHOICE_FIXED, BUSY, RETRY_LATER, RECOVERY_ERROR, STALE_CONTEXT}

    /**
     * Current is a read-only proof of the original actor/session, checked between forward effects.
     * Losing it stops new delivery; an effect already performed still owes its exact commit and
     * checkpoint before control returns. The adapter retains responsibility for that original target.
     */
    public record Request(PlayerResearchState playerState, ResearchDefinition definition, int level,
                          ResourceLocation selectedGroupId, long currentWorldTime, RewardClaimCheckpoint checkpoint,
                          ItemDeliveryAdapter itemDelivery, BooleanSupplier current) {
        public Request(PlayerResearchState playerState, ResearchDefinition definition, int level,
                       ResourceLocation selectedGroupId, long currentWorldTime, RewardClaimCheckpoint checkpoint,
                       ItemDeliveryAdapter itemDelivery) {
            this(playerState, definition, level, selectedGroupId, currentWorldTime, checkpoint, itemDelivery, () -> true);
        }

        public Request {
            Objects.requireNonNull(playerState, "playerState");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(itemDelivery, "itemDelivery");
            Objects.requireNonNull(current, "current");
            if (level < 1 || level > definition.maxLevel() || currentWorldTime < 0)
                throw new IllegalArgumentException("Invalid reward claim request");
        }
    }

    public record Result(Status status, String message, @Nullable RewardClaim claimed) {
    }
}
