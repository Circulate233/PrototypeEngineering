package com.circulation.prototype_eng.api.research.station;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchCompletion;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * One team's material consumer. Each segment uses the actual recipe lifecycle and source lanes;
 * only its successful END grants numeric progress and, when applicable, research completion.
 * The host retains this owner through pending settlement, before pruning, saving or starting work.
 */
public final class ResearchStationWork {
    private final Thread owner = Thread.currentThread();
    private final TeamResearchQueue queue;
    private final TeamOwnedResearch team;
    private final Map<UUID, PlayerResearchState> players;
    private final TeamResearchCompletion completion;
    private final Random random = new Random();
    @Nullable private Segment segment;
    @Nullable private Cycle cycle;
    private Result result = new Result(Status.WAITING, null, ProcessResult.SUCCESS);
    private long lastGrantedTime = -1;
    private boolean executing;

    public ResearchStationWork(TeamResearchQueue queue, TeamOwnedResearch team, Map<UUID, PlayerResearchState> players) {
        this.queue = Objects.requireNonNull(queue);
        this.team = Objects.requireNonNull(team);
        this.players = Objects.requireNonNull(players);
        if (!queue.teamId().equals(team.teamId()) || queue.registry() != team.registry()) {
            throw new IllegalArgumentException("Station research sources have different owners");
        }
        completion = new TeamResearchCompletion(queue.registry());
    }

    /** Renew these exact physical sources between ticks; never replace a partially paid segment. */
    @Nullable public ResearchStations.Capture sources() { requireOwner(); return segment == null ? null : segment.sources; }

    @Nullable public RecipeProcess.State materialState() { requireOwner(); return segment == null ? null : segment.process.state(); }

    public Result result() { requireOwner(); return result; }
    public boolean pending() { requireOwner(); return cycle != null; }

    /**
     * At most one segment per call. A retained cycle is resolved before inspecting the supplied new
     * capture. No ordinary numeric reservation is made; ResearchStations owns the shared leases.
     */
    public Result advance(ResearchStations.Capture sources, Context context, PlannedTaskExecutor tasks, PlannedDeadline deadline) {
        requireOwner();
        Objects.requireNonNull(sources);
        Objects.requireNonNull(context);
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(deadline);
        executing = true;
        try {
            if (cycle != null) { return drive(deadline); }
            if (context.time() < lastGrantedTime) { throw new IllegalArgumentException("Research segment time moved backwards"); }
            if (context.time() == lastGrantedTime) { return set(Status.WAITING, null); }
            if (segment != null && !segment.process.persistenceReady()) { return set(Status.FAILED, null); }
            if (segment != null && segment.process.state().lifecycle().isTerminal()) { return set(Status.FAILED, null); }
            if (!queue.teamId().equals(sources.teamId())) { throw new IllegalArgumentException("Station capture belongs to another team"); }
            if (queue.busy() || context.members().isEmpty() || !context.current().getAsBoolean() || !sources.current()) { return set(Status.WAITING, null); }
            var head = queue.activeHead().orElse(null);
            if (head == null) { return set(Status.WAITING, null); }
            if (segment != null && (!segment.key.equals(head.key()) || segment.sources.primary() != sources.primary()
                || segment.sources.hatches() != sources.hatches() || !segment.sources.stationIds().equals(sources.stationIds()))) {
                return set(Status.WAITING, null);
            }
            var stage = queue.registry().require(head.key().researchId()).stageForLevel(head.key().targetLevel());
            if (stage.researchTier() == 0 || sources.requiredTier() < stage.researchTier()) { return set(Status.WAITING, null); }
            var plan = queue.planSegment(head.key(), sources.stationIds(), sources.numericResources(), context.time()).orElse(null);
            if (plan == null || !context.current().getAsBoolean() || !sources.current()) { return set(Status.WAITING, null); }
            if (segment == null) {
                var process = RecipeProcess.materialCycle(sources.primary(), head.key().researchId(), plan.materialRequirements(), sources.hatches(), random.nextLong());
                segment = new Segment(head.key(), sources, process);
            }
            var attempt = new Cycle(segment, sources, context, plan);
            attempt.coordinator = PlannedRecipeCoordinator.forProcess(tasks, segment.process, deadline,
                new PlannedCancellationToken(), attempt::current, attempt::participants);
            cycle = attempt;
            return drive(deadline);
        } finally { executing = false; }
    }

    /** Old-tick cleanup retains the original participants and never obtains a new material plan. */
    public Result resolve(PlannedDeadline cleanupDeadline) {
        requireOwner();
        Objects.requireNonNull(cleanupDeadline);
        executing = true;
        try { return cycle == null ? result : drive(cleanupDeadline); }
        finally { executing = false; }
    }

    /** Physical exit alone does not release the process, receipts or storage fence. */
    @SuppressWarnings("unused") // Scheduler hosts join the retained source actions before retirement.
    public void awaitExit() {
        requireOwner();
        executing = true;
        try { if (cycle != null) { Objects.requireNonNull(cycle.coordinator).awaitExit(); } }
        finally { executing = false; }
    }

    /** Cancel only after exact pending settlement. Already completed phases are not refunded. */
    public boolean cancel(PlannedDeadline cleanupDeadline) {
        requireOwner();
        Objects.requireNonNull(cleanupDeadline);
        executing = true;
        try {
            if (cycle != null) {
                Objects.requireNonNull(cycle.coordinator).cancel(PlannedCancellationToken.Reason.EXPLICIT_CANCEL);
                drive(cleanupDeadline);
                if (cycle != null) { return false; }
            }
            if (segment == null) { return true; }
            if (!segment.process.persistenceReady()) { return false; }
            if (!segment.process.state().lifecycle().isTerminal()) { segment.process.cancel(); }
            result = new Result(Status.CANCELLED, null, segment.process.state().result());
            segment = null;
            return true;
        } finally { executing = false; }
    }

    private Result drive(PlannedDeadline deadline) {
        var attempt = Objects.requireNonNull(cycle);
        try { Objects.requireNonNull(attempt.coordinator).advance(deadline); }
        catch (RuntimeException | Error failure) {
            try { finish(attempt); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
        finish(attempt);
        return result;
    }

    private void finish(Cycle attempt) {
        if (!Objects.requireNonNull(attempt.coordinator).finished()) { set(Status.PENDING, null); return; }
        cycle = null;
        var grant = attempt.grant;
        if (grant != null && grant.resolution() == ReversibleTransaction.Resolution.COMPLETED) {
            if (attempt.segment.process.state().lifecycle() != RecipeProcess.Lifecycle.COMPLETED) {
                throw new IllegalStateException("Research grant completed without its material END");
            }
            var outcome = grant.result();
            lastGrantedTime = attempt.plan.worldTime();
            set(outcome.completedResearch() ? Status.COMPLETED : Status.ADVANCED, outcome);
            segment = null;
        } else {
            set(attempt.coordinator.failure() != null || !attempt.segment.process.persistenceReady()
                || attempt.segment.process.state().lifecycle().isTerminal() ? Status.FAILED : Status.WAITING, null);
        }
    }

    private Result set(Status status, @Nullable TeamResearchQueue.GrantResult grant) {
        result = new Result(status, grant, segment == null ? ProcessResult.SUCCESS : segment.process.state().result());
        return result;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner || executing) { throw new IllegalStateException("Station research requires its idle owner thread"); }
    }

    public enum Status { WAITING, PENDING, ADVANCED, COMPLETED, FAILED, CANCELLED }

    public record Result(Status status, @Nullable TeamResearchQueue.GrantResult grant, ProcessResult materials) {
        public Result { Objects.requireNonNull(status); Objects.requireNonNull(materials); }
    }

    /** Current team membership proof is distinct from the network's physical capture. */
    public record Context(long time, ObjectSet<UUID> members, BooleanSupplier current) {
        public Context(long time, Collection<UUID> members, BooleanSupplier current) {
            this(time, new ObjectLinkedOpenHashSet<>(members), current);
        }
        public Context {
            if (time < 0) { throw new IllegalArgumentException("Negative research time"); }
            members = ObjectSets.unmodifiable(new ObjectLinkedOpenHashSet<>(members));
            for (var member : members) { Objects.requireNonNull(member); }
            Objects.requireNonNull(current);
        }
    }

    private record Segment(TeamResearchQueue.Key key, ResearchStations.Capture sources, RecipeProcess process) { }

    private final class Cycle {
        private final Segment segment;
        private final ResearchStations.Capture sources;
        private final Context context;
        private final TeamResearchQueue.SegmentPlan plan;
        @Nullable private PlannedRecipeCoordinator coordinator;
        @Nullable private TeamResearchQueue.GrantMutation grant;
        private boolean grantCommitted;

        private Cycle(Segment segment, ResearchStations.Capture sources, Context context, TeamResearchQueue.SegmentPlan plan) {
            this.segment = segment; this.sources = sources; this.context = context; this.plan = plan;
        }

        private boolean current() {
            return sources.current() && context.current().getAsBoolean()
                && queue.epoch() == (grantCommitted ? plan.queueEpoch() + 1 : plan.queueEpoch());
        }

        @Nullable private List<? extends ReversibleTransaction> participants(RecipeProcess.Stage stage) {
            if (stage.phase() != RecipeStage.END) { return List.of(); }
            if (!current() || queue.busy()) { return null; }
            var prepared = plan.completesResearch() ? completion.prepare(plan.key().researchId(), plan.key().targetLevel(),
                context.members(), players, team, context.time()) : null;
            if (plan.completesResearch() && prepared == null) { return null; }
            try { grant = queue.prepareGrantSegment(plan); }
            catch (RuntimeException | Error failure) {
                if (prepared != null) {
                    try { prepared.rollback(); }
                    catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
                }
                throw failure;
            }
            var tracked = new ReversibleTransaction() {
                @Override public boolean prepareCommit() { return current(); }
                @Override public void commit() { Objects.requireNonNull(grant).commit(); grantCommitted = true; }
                @Override public boolean validateCommit() { return current(); }
                @Override public void complete() { Objects.requireNonNull(grant).complete(); }
                @Override public void rollback() { Objects.requireNonNull(grant).rollback(); }
                @Override public Resolution resolution() { return Objects.requireNonNull(grant).resolution(); }
            };
            return prepared == null ? List.of(tracked) : List.of(tracked, prepared);
        }
    }
}
