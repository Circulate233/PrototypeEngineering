package com.circulation.prototype_eng.api.recipe.process;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import com.circulation.prototype_eng.recipe.RecipeAbortCleanup;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Isolated requirement/extension state for one work slot. A scheduler may run different processes
 * concurrently but must never execute the same process concurrently. Its machine attribute baseline
 * must remain stable during that execution. Persistence requires a quiescent, exclusively owned
 * process; tick() is the synchronous single-process lifecycle, independent of its scheduler.
 */
public final class RecipeProcess {
    private final MachineInstance owner;
    private final MachineRecipe recipe;
    private final long seed;
    private final ProbabilityTuning probabilityTuning;
    private final ComponentMap<RecipeProcess> components = new ComponentMap<>();
    private final MachineAttributes attributes;
    private State state = new State(Lifecycle.NEW, 0, 0, ProcessResult.SUCCESS);
    private boolean ticking;
    private int acquiredTransactions;
    private boolean persistenceFailure;
    private long stageVersion;
    @Nullable
    private TickSession activeSession;
    @Nullable
    private MachineWorkSlots host;
    @Nullable
    private Tick activeTick;
    @Nullable
    private RequirementPath activeRequirement;

    public RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed) {
        this(owner, recipe, seed, ProbabilityTuning.defaultTuning());
    }

    public RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning probabilityTuning) {
        this.owner = Objects.requireNonNull(owner);
        this.recipe = Objects.requireNonNull(recipe);
        this.seed = seed;
        this.probabilityTuning = Objects.requireNonNull(probabilityTuning);
        attributes = new MachineAttributes(owner.attributes(), 1);
    }

    public MachineInstance owner() {
        return owner;
    }

    /** Transient attachment identity, never a persisted scheduler or world authority. */
    @Nullable
    public MachineWorkSlots host() { return host; }

    /** Host assembly boundary; a process may never occupy two work slots or hosts. */
    public void attachTo(MachineWorkSlots newHost) {
        requirePersistenceBoundary();
        if (Objects.requireNonNull(newHost).owner() != owner || host != null) {
            throw new IllegalStateException("Process already attached or belongs to a different machine");
        }
        host = newHost;
    }

    public void detachFrom(MachineWorkSlots previousHost) {
        requirePersistenceBoundary();
        if (host == null || host != previousHost) { throw new IllegalStateException("Process is not attached to the expected host"); }
        host = null;
    }

    public MachineRecipe recipe() {
        return recipe;
    }

    /** Downstream mods can retain the seed when restoring deterministic process state. */
    @SuppressWarnings("unused")
    public long seed() {
        return seed;
    }

    public ComponentMap<RecipeProcess> components() {
        return components;
    }

    /** Process-local bases/modifiers overlay the live machine baseline without mutating it. */
    public MachineAttributes attributes() {
        return attributes;
    }

    /** Frozen per process so a global tuning change cannot reroll a blocked phase. */
    public ProbabilityTuning probabilityTuning() {
        return probabilityTuning;
    }

    public State state() {
        return state;
    }

    /** Only complete, quiescent stage boundaries may be serialized; no live transaction is resumable. */
    public void requirePersistenceBoundary() {
        requireQuiescent();
        if (persistenceFailure) {
            throw new IllegalStateException("Process is executing, has acquired transactions, or failed terminal cleanup");
        }
    }

    /** Rejects live work; unlike persistence this permits observing a faulted but inactive process. */
    public void requireQuiescent() {
        if (ticking || activeSession != null || activeTick != null || activeRequirement != null || acquiredTransactions != 0) {
            throw new IllegalStateException("Process is executing or has acquired transactions");
        }
    }

    /** Framework failure marker for an exceptional acquisition/cleanup outside tick(). */
    public void invalidatePersistenceBoundary() { persistenceFailure = true; }

    public boolean cancel() { return cancel(RecipeText.Cancelled.failure()); }

    /** Explicit terminal cancellation at an exclusive, transaction-free boundary; no END or refund. */
    public boolean cancel(ProcessResult.Failure reason) {
        return host == null ? cancelFrom(null, reason) : host.cancel(this, reason);
    }

    /** Work-slot assembly boundary. Hosted callers use cancel(), which coordinates the slot result. */
    public boolean cancelFrom(@Nullable MachineWorkSlots expectedHost, ProcessResult.Failure reason) {
        Objects.requireNonNull(reason);
        if (host != expectedHost) { throw new IllegalArgumentException("Process belongs to a different cancellation host"); }
        requirePersistenceBoundary();
        if (state.lifecycle().isTerminal()) { return false; }
        ticking = true;
        try {
            state = new State(Lifecycle.CANCELLED, state.progress(), state.successfulTicks(), reason, state.started());
            RecipeAbortCleanup.run(this);
            return true;
        } catch (RuntimeException | Error failure) {
            persistenceFailure = true;
            throw failure;
        } finally {
            ticking = false;
        }
    }

    /** Constructs an unpublished restored process. Its components still require restoration and validation. */
    public static RecipeProcess restored(MachineInstance owner, MachineRecipe recipe, long seed,
                                          ProbabilityTuning tuning, State state) {
        Objects.requireNonNull(state);
        if (state.lifecycle() == Lifecycle.COMPLETED && state.progress() < recipe.durationTicks()) {
            throw new IllegalArgumentException("Completed process has insufficient work progress");
        }
        var process = new RecipeProcess(owner, recipe, seed, tuning);
        process.state = state;
        return process;
    }

    /** Frozen scaled/unscaled timeline for requirements executing the current TICK stage. */
    public Tick tickContext() {
        return Objects.requireNonNull(activeTick, "No TICK stage is executing");
    }

    /** The executing definition's stable path, including checkpoint/selection wrapper ancestry. */
    public RequirementPath requirementPath() {
        return Objects.requireNonNull(activeRequirement, "No requirement callback is executing");
    }

    /** Framework boundary used by RecipeRequirement.acquire; all returned callbacks retain this scope. */
    public RequirementTransaction acquireRequirement(RecipeRequirement<?> requirement, Supplier<RequirementTransaction> acquisition) {
        if (state.lifecycle().isTerminal()) { throw new IllegalStateException("Cannot acquire requirements on a terminal process"); }
        var path = requirement.path(activeRequirement);
        var transaction = withRequirementPath(path, () -> Objects.requireNonNull(acquisition.get(), "requirement transaction"));
        if (transaction == RequirementTransaction.SUCCESS) { return transaction; }
        acquiredTransactions = Math.incrementExact(acquiredTransactions);
        return new ScopedRequirement(this, path, transaction);
    }

    /** Requirement cancellation uses the same parent/child scope without acquiring a new transaction. */
    public void cancelRequirement(RecipeRequirement<?> requirement, Runnable cleanup) {
        withRequirementPath(requirement.path(activeRequirement), () -> {
            cleanup.run();
            return null;
        });
    }

    private <T> T withRequirementPath(RequirementPath path, Supplier<T> action) {
        RequirementPath previous = activeRequirement;
        activeRequirement = path;
        try {
            return action.get();
        } finally {
            activeRequirement = previous;
        }
    }

    private static final class ScopedRequirement implements RequirementTransaction {
        private final RecipeProcess process;
        private final RequirementPath path;
        private final RequirementTransaction delegate;
        @Nullable private final Tick tick;
        @Nullable private Resolution intendedTerminal;
        private Resolution terminal = Resolution.UNKNOWN;
        private boolean commitAttempted;
        private boolean terminalExecuting;
        private boolean queryExecuting;
        private boolean uncertain;
        private boolean closed;

        private ScopedRequirement(RecipeProcess process, RequirementPath path, RequirementTransaction delegate) {
            this.process = process;
            this.path = path;
            this.delegate = delegate;
            tick = process.activeTick;
        }

        @Override public ProcessResult result() { return scoped(delegate::result); }
        @Override public List<? extends ResourceReservation> reservations() {
            return scoped(delegate::reservations);
        }
        @Override public void commit() {
            if (closed || intendedTerminal != null || commitAttempted || queryExecuting) {
                throw new IllegalStateException("Cannot replay or commit a resolving requirement transaction");
            }
            commitAttempted = true;
            try { scoped(delegate::commit); }
            catch (RuntimeException | Error failure) { process.persistenceFailure = true; throw failure; }
        }
        @Override public void complete() { close(Resolution.COMPLETED, delegate::complete); }
        @Override public void rollback() { close(Resolution.ROLLED_BACK, delegate::rollback); }

        @Override public Resolution resolution() {
            if (terminalExecuting || queryExecuting) { throw new IllegalStateException("Cannot reenter a requirement recovery callback"); }
            if (closed) { return terminal; }
            queryExecuting = true;
            try {
                var actual = Objects.requireNonNull(scoped(delegate::resolution));
                if (uncertain && actual == intendedTerminal) { confirmTerminal(actual); }
                return actual;
            } finally { queryExecuting = false; }
        }

        private void close(Resolution expected, Runnable action) {
            if (terminalExecuting || queryExecuting) { throw new IllegalStateException("Cannot reenter a requirement terminal callback"); }
            if (closed) {
                if (terminal != expected) { throw new IllegalStateException("Requirement already resolved in the opposite direction"); }
                return;
            }
            if (intendedTerminal != null && intendedTerminal != expected) {
                throw new IllegalStateException("Cannot change a requirement's terminal decision");
            }
            if (uncertain) {
                var actual = resolution();
                if (closed) { return; }
                if (actual != Resolution.REVERSIBLE) {
                    throw new IllegalStateException("Cannot retry a requirement without an exact recovery proof");
                }
            }
            intendedTerminal = expected;
            try {
                terminalExecuting = true;
                try { scoped(action); }
                finally { terminalExecuting = false; }
                confirmTerminal(expected);
            } catch (RuntimeException | Error failure) {
                uncertain = true;
                process.persistenceFailure = true;
                try { resolution(); }
                catch (RuntimeException | Error query) { if (query != failure) { failure.addSuppressed(query); } }
                throw failure;
            }
        }

        private void confirmTerminal(Resolution actual) {
            if (closed) { return; }
            terminal = actual;
            closed = true;
            uncertain = false;
            process.acquiredTransactions--;
        }

        private void scoped(Runnable action) {
            scoped(() -> {
                action.run();
                return null;
            });
        }

        private <T> T scoped(Supplier<T> action) {
            Tick previous = process.activeTick;
            process.activeTick = tick;
            try { return process.withRequirementPath(path, action); }
            finally { process.activeTick = previous; }
        }
    }

    /**
     * Temporarily supplies a nested requirement's timeline without advancing this process.
     * Checkpoints use a zero-width context for a full child lifecycle executed at time zero.
     * This scope has the same exclusive-owner requirement as tick().
     */
    public <T> T withTickContext(Tick context, Supplier<T> action) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(action);
        Tick previous = activeTick;
        activeTick = context;
        try {
            return action.get();
        } finally {
            activeTick = previous;
        }
    }

    /**
     * PM's START -> TICK -> END loop, including the completion check before and after TICK.
     * Blocked retries the same stage; Failure is terminal. Call once per scheduled machine tick.
     * Component callbacks run pre/tick before processing and post afterwards, including blockage.
     */
    public ProcessResult tick() {
        var session = beginTick();
        if (session == null) { return state.result(); }
        try {
            Stage stage;
            while ((stage = session.nextStage()) != null) {
                try { stage.acquire(); }
                catch (RuntimeException | Error failure) {
                    try { stage.rollbackSynchronously(); }
                    catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
                    throw failure;
                }
                stage.settleSynchronously();
            }
            return state.result();
        } catch (RuntimeException | Error failure) {
            session.recordFailure(failure);
            throw failure;
        } finally {
            // PM's ordinary body always exits through post, even with uncertain cleanup.
            // The live session still pins persistence until its remaining stage is confirmed.
            session.finishSynchronousBody();
        }
    }

    /**
     * Begins one scheduled tick. Null means terminal or paused; no component callback ran.
     * The owner must retain the session through all stage rounds and finish it after settlement.
     * This is deliberately not AutoCloseable: unresolved resources must defer planned post callbacks.
     */
    @Nullable
    public TickSession beginTick() {
        requirePersistenceBoundary();
        if (state.lifecycle().isTerminal() || workPaused()) { return null; }
        var session = new TickSession(this);
        activeSession = session;
        session.executing = true;
        try {
            session.callbacks = components.beginTick(this);
            return session;
        } catch (RuntimeException | Error failure) {
            persistenceFailure = true;
            activeSession = null;
            throw failure;
        } finally { session.executing = false; }
    }

    /** Recovery owner access after an ordinary tick threw with unconfirmed cleanup. */
    @Nullable
    public TickSession pendingTick() { return activeSession; }

    /** One owner-thread component dispatch and a cursor that permits at most one TICK attempt. */
    public static final class TickSession {
        private final RecipeProcess process;
        private final Thread ownerThread = Thread.currentThread();
        @Nullable private final MachineWorkSlots host;
        @Nullable private ComponentMap.TickSession<RecipeProcess> callbacks;
        @Nullable private Stage current;
        @Nullable private Throwable failure;
        private boolean executing;
        private boolean tickAttempted;
        private boolean stopped;
        private boolean postDone;

        private TickSession(RecipeProcess process) {
            this.process = process;
            host = process.host;
        }

        /** Creates the lease before acquisition, so even an empty stage prevents reentry/save. */
        @Nullable
        public Stage nextStage() {
            requireOwner();
            if (current != null) { throw new IllegalStateException("Current recipe stage is still leased"); }
            if (stopped || process.workPaused() || process.state.lifecycle().isTerminal()) { return null; }
            RecipeStage phase;
            if (process.state.lifecycle() == Lifecycle.NEW) { phase = RecipeStage.START; }
            else if (process.state.progress() >= process.recipe.durationTicks()) { phase = RecipeStage.END; }
            else if (!tickAttempted) { phase = RecipeStage.TICK; }
            else { return null; }
            process.stageVersion = Math.incrementExact(process.stageVersion);
            current = new Stage(this, phase, process.stageVersion);
            if (phase == RecipeStage.TICK) { tickAttempted = true; }
            return current;
        }

        /** The retained stage remains available for exact cleanup after acquisition/body failure. */
        @Nullable
        public Stage currentStage() { requireOwner(); return current; }

        /** Stops later rounds and retains the original fault, including faults from source lanes. */
        public void recordFailure(Throwable cause) {
            requireThread();
            Objects.requireNonNull(cause);
            if (failure == null) { failure = cause; }
            else { suppress(failure, cause); }
            stopped = true;
            process.persistenceFailure = true;
        }

        /** Finishes planned post only after the current lease was accurately settled. */
        public void finish() {
            requireOwner();
            if (current != null) { throw new IllegalStateException("Cannot finish an unsettled recipe tick"); }
            finishSynchronousBody();
        }

        private void finishSynchronousBody() {
            requireOwner();
            stopped = true;
            executing = true;
            try {
                if (!postDone) {
                    postDone = true;
                    Objects.requireNonNull(callbacks).finish(failure);
                }
            } catch (RuntimeException | Error post) {
                recordFailure(post);
                throw post;
            } finally {
                executing = false;
                if (current == null) { process.activeSession = null; }
            }
        }

        private void requireThread() {
            if (Thread.currentThread() != ownerThread || process.activeSession != this || process.host != host) {
                throw new IllegalStateException("Stale recipe tick session or incorrect owner");
            }
        }

        private void requireOwner() {
            requireThread();
            if (executing) { throw new IllegalStateException("Recipe tick callback is executing"); }
        }
    }

    /**
     * Exact current-stage acquisition. Use synchronous settlement or transfer a whole round to one
     * ResourceBatch; never both. The handle cannot advance its process until that boundary is proven.
     */
    public static final class Stage {
        private final TickSession session;
        private final RecipeStage phase;
        private final long version;
        private final State before;
        private final State next;
        @Nullable private final Tick tick;
        private final RecipeStageExecutor.Acquisition acquisition;
        @Nullable private ResourceBatch batch;
        private boolean acquired;
        private boolean synchronous;
        private ReversibleTransaction.Resolution transition = ReversibleTransaction.Resolution.REVERSIBLE;

        private Stage(TickSession session, RecipeStage phase, long version) {
            this.session = session;
            this.phase = phase;
            this.version = version;
            var process = session.process;
            before = process.state;
            next = switch (phase) {
                case START -> new State(Lifecycle.RUNNING, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
                case TICK -> new State(Lifecycle.RUNNING,
                    before.progress() + Math.max(0, process.attributes.value(StandardMachineAttributes.PROCESS_SPEED, 1)),
                    Math.incrementExact(before.successfulTicks()), ProcessResult.SUCCESS);
                case END -> new State(Lifecycle.COMPLETED, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
            };
            tick = phase == RecipeStage.TICK ? new Tick(before.progress(), next.progress(),
                before.successfulTicks(), next.successfulTicks()) : null;
            acquisition = RecipeStageExecutor.prepare(process, phase, new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public void commit() {
                    requireLive();
                    if (session.failure != null || acquisition.failure() != null || acquisition.result() != ProcessResult.SUCCESS
                        || transition != Resolution.REVERSIBLE || process.state != before) {
                        throw new IllegalStateException("Cannot commit a rejected or stale recipe stage");
                    }
                    process.state = next;
                }
                @Override public void complete() {
                    requireLive();
                    if (process.state != next || transition != Resolution.REVERSIBLE) {
                        throw new IllegalStateException("Recipe transition did not commit");
                    }
                    transition = Resolution.COMPLETED;
                }
                @Override public void rollback() {
                    requireLive();
                    if (transition != Resolution.REVERSIBLE) { throw new IllegalStateException("Recipe transition is terminal"); }
                    process.state = before;
                    transition = Resolution.ROLLED_BACK;
                }
                @Override public Resolution resolution() { requireLive(); return transition; }
            });
        }

        public RecipeStage phase() { return phase; }

        /** Stops before acquisition without invoking requirements, for cancellation/retirement. */
        public void discard() {
            requireOwner();
            if (acquired) { throw new IllegalStateException("Acquired recipe stage must settle its participants"); }
            session.stopped = true;
            session.current = null;
        }

        /** A throwing acquire still leaves this handle owning its partial acquisition. */
        public void acquire() {
            requireOwner();
            if (acquired) { throw new IllegalStateException("Recipe stage was already acquired"); }
            acquired = true;
            session.executing = true;
            var process = session.process;
            Tick previous = process.activeTick;
            process.activeTick = tick;
            try { acquisition.acquire(); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { process.activeTick = previous; session.executing = false; }
        }

        public ProcessResult result() { requireAcquired(); return acquisition.result(); }

        /** Preserves ResourceTransactions' all-domain lock and terminal notification semantics. */
        public ProcessResult settleSynchronously() {
            requireSynchronousStart();
            session.executing = true;
            try { return acquisition.settleSynchronously(); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { finishSynchronousAttempt(); }
        }

        /** Rolls back a rejected/abandoned or partially acquired stage without replaying commit. */
        public void rollbackSynchronously() {
            requireSynchronousStart();
            session.stopped = true;
            session.executing = true;
            try { acquisition.rollbackSynchronously(); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { finishSynchronousAttempt(); }
        }

        /** Retries only terminal cleanup proven safe by the exact synchronous participants. */
        public boolean resolveSynchronously() {
            requireAcquired();
            if (!synchronous || batch != null) { throw new IllegalStateException("No synchronous settlement to recover"); }
            session.executing = true;
            try { acquisition.resolveSynchronously(); return acquisition.resolved(); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { finishSynchronousAttempt(); }
        }

        private void finishSynchronousAttempt() {
            try {
                if (acquisition.resolved()) { settled(transition == ReversibleTransaction.Resolution.COMPLETED); }
            } catch (RuntimeException | Error cleanup) {
                session.recordFailure(cleanup);
                throw cleanup;
            } finally { session.executing = false; }
        }

        /**
         * Transfers a round's exact participants together. Busy domains return null and leave every
         * handle unbound. Rejected stages may only roll back; transition guards reject illegal commit.
         * Source actions still run on their assigned lanes; the owner performs business and finish.
         */
        @Nullable
        public static ResourceBatch tryAcquireBatch(List<Stage> stages) {
            var round = new ObjectArrayList<>(stages);
            if (round.isEmpty()) { throw new IllegalArgumentException("Recipe round is empty"); }
            var unique = new ReferenceOpenHashSet<Stage>();
            var reservations = new ObjectArrayList<ResourceReservation>();
            var transactions = new ObjectArrayList<RequirementTransaction>();
            for (var stage : round) {
                stage.requireAcquired();
                if (!unique.add(stage) || stage.synchronous || stage.batch != null || !stage.acquisition.inventoryComplete()) {
                    throw new IllegalStateException("Recipe stage cannot transfer its acquisition");
                }
                reservations.addAll(stage.acquisition.reservations());
                transactions.addAll(stage.acquisition.transactions());
            }
            for (var stage : round) { stage.session.executing = true; }
            try {
                var bound = ResourceBatch.tryAcquire(reservations, transactions);
                if (bound != null) { for (var stage : round) { stage.batch = bound; } }
                return bound;
            } finally { for (var stage : round) { stage.session.executing = false; } }
        }

        /**
         * Acknowledges only this handle's bound batch, after all actions exited and isolation was
         * released. False leaves the stage/post obligation pinned. Pass any source/owner/finish fault
         * so a terminal notification failure cannot silently permit later stages or persistence.
         */
        public boolean settleBatch(@Nullable Throwable failure) {
            requireAcquired();
            var bound = Objects.requireNonNull(batch, "Recipe stage has no bound batch");
            if (failure != null) { session.recordFailure(failure); }
            if (!bound.finished()) { return false; }
            session.executing = true;
            try { settled(bound.decision() == ResourceBatch.Decision.COMPLETE); return true; }
            catch (RuntimeException | Error cleanup) { session.recordFailure(cleanup); throw cleanup; }
            finally { session.executing = false; }
        }

        private void settled(boolean committed) {
            var process = session.process;
            session.current = null;
            if (!committed) { session.stopped = true; }
            if (session.failure != null) { return; }
            ProcessResult result = acquisition.result();
            if (!committed && result == ProcessResult.SUCCESS) { result = RecipeText.StorageChanged.blocked(); }
            if (result != ProcessResult.SUCCESS) {
                session.stopped = true;
                process.state = new State(result instanceof ProcessResult.Failure ? Lifecycle.FAILED : before.lifecycle(),
                    before.progress(), before.successfulTicks(), result, before.started());
                if (result instanceof ProcessResult.Failure) { RecipeAbortCleanup.run(process); }
            }
        }

        private void requireSynchronousStart() {
            requireAcquired();
            if (synchronous || batch != null) { throw new IllegalStateException("Recipe stage settlement already started"); }
            synchronous = true;
        }

        private void requireAcquired() {
            requireOwner();
            if (!acquired) { throw new IllegalStateException("Recipe stage has not acquired requirements"); }
        }

        private void requireOwner() { session.requireOwner(); requireLive(); }

        private void requireLive() {
            session.requireThread();
            var process = session.process;
            if (session.current != this || process.stageVersion != version || process.state != before && process.state != next) {
                throw new IllegalStateException("Stale recipe stage handle or state");
            }
        }
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) { primary.addSuppressed(secondary); }
    }

    private boolean workPaused() {
        var lifecycle = owner.lifecycle();
        return lifecycle != null && lifecycle.retired() || !owner.recipeWorkAllowed();
    }

    public enum Lifecycle {
        NEW, RUNNING, COMPLETED, FAILED, CANCELLED;

        public boolean isTerminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
    }

    /** Progress is absolute scaled work ticks; successfulTicks is the unscaled deterministic clock. */
    public record State(Lifecycle lifecycle, double progress, long successfulTicks, ProcessResult result, boolean started) {
        public State(Lifecycle lifecycle, double progress, long successfulTicks, ProcessResult result) {
            this(lifecycle, progress, successfulTicks, result, lifecycle == Lifecycle.RUNNING || lifecycle == Lifecycle.COMPLETED);
        }

        public State {
            Objects.requireNonNull(lifecycle);
            Objects.requireNonNull(result);
            if (!Double.isFinite(progress) || progress < 0 || successfulTicks < 0) {
                throw new IllegalArgumentException("Invalid process progress");
            }
            if (!started && (progress != 0 || successfulTicks != 0) || lifecycle == Lifecycle.NEW && started
                || (lifecycle == Lifecycle.RUNNING || lifecycle == Lifecycle.COMPLETED) && !started
                || (lifecycle == Lifecycle.FAILED || lifecycle == Lifecycle.CANCELLED) != (result instanceof ProcessResult.Failure)
                || lifecycle == Lifecycle.COMPLETED && result != ProcessResult.SUCCESS) {
                throw new IllegalArgumentException("Inconsistent process lifecycle and committed START state");
            }
        }
    }

    public record Tick(double progressBefore, double progressAfter, long indexBefore, long indexAfter) {
        public Tick {
            if (!Double.isFinite(progressBefore) || !Double.isFinite(progressAfter) || progressBefore < 0
                || progressAfter < progressBefore || indexBefore < 0 || indexAfter < indexBefore) {
                throw new IllegalArgumentException("Invalid frozen tick timeline");
            }
        }
    }

    /**
     * Repeatable phase/context selection, matching PM's seed XOR salt convention.
     * Downstream requirement implementations use a salt to isolate their random choices.
     */
    @SuppressWarnings("unused")
    public Random random(String salt) {
        return new Random(seed ^ salt.hashCode());
    }
}
