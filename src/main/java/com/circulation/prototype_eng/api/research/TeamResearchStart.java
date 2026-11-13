package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Owner-side queue admission. Permission and the real requester condition run before enqueue;
 * admission never consumes materials or computing resources. Device facts and current() come from
 * the server's captured team/network state, not a client request. Manual session creation follows
 * a successful tier-zero admission and remains a separate execution responsibility.
 */
public final class TeamResearchStart {
    private final Thread owner = Thread.currentThread();
    private final ResearchRegistry registry;
    private final ResearchEligibility eligibility;
    private final ResearchPermissionPolicy permissions;
    private boolean executing;

    public TeamResearchStart(ResearchRegistry registry,
                             ResearchPermissionPolicy permissions) {
        this.registry = Objects.requireNonNull(registry);
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research start requires frozen definitions");
        }
        this.permissions = Objects.requireNonNull(permissions);
        eligibility = new ResearchEligibility(registry);
    }

    private static Result denied(Status status) {
        return new Result(status, null, null);
    }

    public Result start(Request request) {
        if (Thread.currentThread() != owner || executing) {
            throw new IllegalStateException("Research start requires its idle owner thread");
        }
        Objects.requireNonNull(request);
        executing = true;
        try {
            return admit(request);
        } finally {
            executing = false;
        }
    }

    private Result admit(Request request) {
        var queue = request.queue();
        var view = request.view();
        var actor = request.permission();
        if (queue.registry() != registry || view.registry() != registry || !queue.teamId().equals(view.teamId())
            || !view.teamId().equals(actor.targetTeamId())) {
            throw new IllegalArgumentException("Research request changed its captured team or registry");
        }
        var definition = registry.require(request.researchId());
        if (request.targetLevel() < 1 || request.targetLevel() > definition.maxLevel()) {
            throw new IllegalArgumentException("Research target is outside its definition");
        }
        if (queue.busy()) {
            return denied(Status.QUEUE_BUSY);
        }
        long epoch = queue.epoch();
        if (!request.current().getAsBoolean()) {
            return denied(Status.STALE_CONTEXT);
        }
        var permission = Objects.requireNonNull(permissions.can(ResearchPermissionPolicy.Action.START_RESEARCH, actor));
        if (permission != ResearchPermissionPolicy.Decision.ALLOWED) {
            return new Result(Status.PERMISSION_DENIED, null, permission);
        }
        // Same-team role flags are inconsistent if the actor is absent from captured membership.
        if (view.teamId().equals(actor.actorTeamId()) && !view.members().contains(actor.actorId())) {
            return new Result(Status.PERMISSION_DENIED, null, ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER);
        }
        if (!request.current().getAsBoolean() || queue.epoch() != epoch || queue.busy()) {
            return denied(Status.STALE_CONTEXT);
        }
        if (!eligibility.structurallyEligible(view, definition.id(), request.targetLevel())) {
            return denied(Status.NOT_STARTABLE);
        }
        int tier = definition.stageForLevel(request.targetLevel()).researchTier();
        var environment = request.environment();
        if (tier > 0) {
            if (environment.networkCenterLevel() == 0) {
                return denied(Status.NO_ACTIVE_CENTER);
            }
            if (environment.dataCenterLevel() < tier) {
                return denied(Status.DATA_CENTER_LEVEL_INSUFFICIENT);
            }
            if (environment.highestResearchStationLevel() < tier) {
                return denied(Status.STATION_LEVEL_INSUFFICIENT);
            }
        }
        if (queue.containsTarget(definition.id(), request.targetLevel())) {
            return denied(Status.DUPLICATE_TARGET);
        }
        if (queue.size() >= TeamResearchQueue.CAPACITY) {
            return denied(Status.QUEUE_FULL);
        }
        if (!definition.startCondition().canStart(new ResearchStartContext(definition.id(), request.targetLevel(),
            actor.actorId(), view.teamId(), view.sharedLevels(), environment.availableResources(), environment.networkCenterLevel()))) {
            return denied(Status.NOT_STARTABLE);
        }
        // Addon callbacks may change the owner state. A cached preview or stale proof never admits work.
        if (!request.current().getAsBoolean() || queue.epoch() != epoch || queue.busy()) {
            return denied(Status.STALE_CONTEXT);
        }
        var entry = queue.enqueue(definition.id(), request.targetLevel(), actor.actorId(), request.serverTime());
        return new Result(Status.STARTED, entry, null);
    }

    public enum Status {
        STARTED, QUEUE_BUSY, QUEUE_FULL, DUPLICATE_TARGET, PERMISSION_DENIED, NOT_STARTABLE,
        NO_ACTIVE_CENTER, DATA_CENTER_LEVEL_INSUFFICIENT, STATION_LEVEL_INSUFFICIENT, STALE_CONTEXT
    }

    /**
     * Active device levels; zero represents absence. Resources retain the current captured vector.
     */
    public record Environment(int networkCenterLevel, int dataCenterLevel, int highestResearchStationLevel,
                              Reference2DoubleMap<NetworkResourceType> availableResources) {
        public Environment {
            if (dataCenterLevel < 0 || highestResearchStationLevel < 0
                || dataCenterLevel > networkCenterLevel || highestResearchStationLevel > networkCenterLevel) {
                throw new IllegalArgumentException("Invalid active research device levels");
            }
            availableResources = NetworkResourceModifier.copyResources(availableResources);
        }
    }

    /**
     * serverTime uses one persisted server timeline, such as overworld totalWorldTime, for every team.
     */
    public record Request(TeamResearchQueue queue, TeamResearchView view, ResearchPermissionContext permission,
                          ResourceLocation researchId, int targetLevel, Environment environment,
                          long serverTime, BooleanSupplier current) {
        public Request {
            Objects.requireNonNull(queue);
            Objects.requireNonNull(view);
            Objects.requireNonNull(permission);
            Objects.requireNonNull(researchId);
            Objects.requireNonNull(environment);
            Objects.requireNonNull(current);
            if (targetLevel < 1 || serverTime < 0) {
                throw new IllegalArgumentException("Invalid research start target or time");
            }
        }
    }

    public record Result(Status status, @Nullable TeamResearchQueue.Entry entry,
                         @Nullable ResearchPermissionPolicy.Decision denial) {
        public Result {
            Objects.requireNonNull(status);
            boolean valid = switch (status) {
                case STARTED -> entry != null && denial == null;
                case PERMISSION_DENIED ->
                    entry == null && denial != null && denial != ResearchPermissionPolicy.Decision.ALLOWED;
                default -> entry == null && denial == null;
            };
            if (!valid) {
                throw new IllegalArgumentException("Inconsistent research admission result");
            }
        }
    }
}
