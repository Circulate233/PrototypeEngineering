package com.circulation.prototype_eng.api.research.station;

import com.circulation.prototype_eng.api.machine.ControllerEnergy;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchCompletion;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Runs after ordinary devices. Materials, station FE, network resources and progress commit or roll back together.
 */
public final class ResearchStationWork {
    private final TeamResearchQueue queue;
    private final TeamOwnedResearch team;
    private final Map<UUID, PlayerResearchState> players;
    private final TeamResearchCompletion completion;
    private Result result = Result.waiting(-1);

    public ResearchStationWork(TeamResearchQueue queue, TeamOwnedResearch team, Map<UUID, PlayerResearchState> players) {
        this.queue = queue;
        this.team = team;
        this.players = players;
        completion = new TeamResearchCompletion(queue.registry());
    }

    public Result result() {
        return result;
    }

    private void waiting(long time) {
        result = Result.waiting(time);
    }

    public Result advance(ResearchStations.Capture sources, Context context) {
        waiting(context.time());
        if (!queue.teamId().equals(sources.teamId()))
            throw new IllegalArgumentException("Research capture belongs to another team");
        var head = queue.activeHead().orElse(null);
        if (head == null || context.members().isEmpty() || !context.current().getAsBoolean() || !sources.current()) {
            sources.releaseUnused(List.of());
            return result;
        }
        var work = Objects.requireNonNull(queue.activeProgress());
        var plan = queue.planSegment(head.key(), sources.rates(), sources.availableResources(),
            sources.availableMaterials(work.snapshot().remainingMaterials()), context.time()).orElse(null);
        if (plan == null) {
            sources.releaseUnused(List.of());
            return result;
        }
        sources.releaseUnused(plan.stationIds());
        var tokens = new ObjectArrayList<ResourceReservation>();
        var payments = new ObjectArrayList<ControllerEnergy.Payment>();
        var business = new ObjectArrayList<ReversibleTransaction>();
        NetworkTeamBudget.Reservation network = null;
        boolean transferred = false;
        Throwable failure = null;
        try {
            if (!sources.reserveMaterials(plan.payments(), tokens) || !sources.reservePower(plan.stationIds(), payments, tokens))
                return result;
            business.addAll(payments);
            if (!plan.grantedResources().isEmpty()) {
                network = sources.reserveNetwork(plan.key().researchId(), plan.grantedResources(), plan.stationIds());
                if (network == null) return result;
            }
            var prepared = plan.completesResearch() ? completion.prepare(plan.key().researchId(), plan.key().targetLevel(),
                context.members(), players, team, context.time()) : null;
            if (plan.completesResearch() && prepared == null) return result;
            if (prepared != null) business.add(prepared);
            var grant = queue.prepareGrantSegment(plan);
            business.add(grant);
            var reservedNetwork = network;
            transferred = true;
            boolean committed = ResourceTransactions.commit(tokens,
                () -> context.current().getAsBoolean() && sources.current() && (reservedNetwork == null || reservedNetwork.commit()),
                () -> business.forEach(ReversibleTransaction::commit),
                () -> business.forEach(ReversibleTransaction::complete),
                () -> rollback(business, reservedNetwork));
            if (committed)
                result = new Result(plan.completesResearch() ? Status.COMPLETED : Status.ADVANCED, context.time(),
                    plan.key(), plan.stationIds(), plan.grantedResources(), plan.capacity());
            else sources.releaseUnused(List.of());
            return result;
        } catch (RuntimeException | Error caught) {
            failure = caught;
            throw caught;
        } finally {
            if (!transferred) {
                try {
                    var reservedNetwork = network;
                    ResourceTransactions.rollback(tokens, () -> rollback(business, reservedNetwork));
                    sources.releaseUnused(List.of());
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) throw cleanup;
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
        }
    }

    private static void rollback(List<ReversibleTransaction> business, @Nullable NetworkTeamBudget.Reservation network) {
        Throwable failure = null;
        for (int index = business.size() - 1; index >= 0; index--) {
            try {
                business.get(index).rollback();
            } catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught;
                else if (failure != caught) failure.addSuppressed(caught);
            }
        }
        if (network != null) {
            try {
                network.rollback();
            } catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught;
                else if (failure != caught) failure.addSuppressed(caught);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    public enum Status {WAITING, ADVANCED, COMPLETED}

    /**
     * Collections belong to this settlement and are transferred read-only to display consumers.
     */
    public record Result(Status status, long time, @Nullable TeamResearchQueue.Key key, List<UUID> stations,
                         Reference2DoubleMap<NetworkResourceType> grants,
                         Reference2DoubleMap<NetworkResourceType> capacity) {
        static Result waiting(long time) {
            return new Result(Status.WAITING, time, null, List.of(), Reference2DoubleMaps.emptyMap(), Reference2DoubleMaps.emptyMap());
        }
    }

    public record Context(long time, Collection<UUID> members, BooleanSupplier current) {
    }
}
