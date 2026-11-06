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
        if (ticking || activeTick != null || activeRequirement != null || acquiredTransactions != 0) {
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
        private boolean closed;

        private ScopedRequirement(RecipeProcess process, RequirementPath path, RequirementTransaction delegate) {
            this.process = process;
            this.path = path;
            this.delegate = delegate;
        }

        @Override public ProcessResult result() { return process.withRequirementPath(path, delegate::result); }
        @Override public List<? extends ResourceReservation> reservations() {
            return process.withRequirementPath(path, delegate::reservations);
        }
        @Override public void commit() {
            if (closed) { throw new IllegalStateException("Cannot commit a closed requirement transaction"); }
            scoped(delegate::commit);
        }
        @Override public void complete() { close(delegate::complete); }
        @Override public void rollback() { close(delegate::rollback); }

        private void close(Runnable action) {
            if (closed) { return; }
            try {
                scoped(action);
            } catch (RuntimeException | Error failure) {
                process.persistenceFailure = true;
                throw failure;
            } finally {
                closed = true;
                process.acquiredTransactions--;
            }
        }

        private void scoped(Runnable action) {
            process.withRequirementPath(path, () -> {
                action.run();
                return null;
            });
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
        requirePersistenceBoundary();
        if (state.lifecycle().isTerminal() || retired()) {
            return state.result();
        }
        ticking = true;
        try {
            components.tick(this, this::advance);
            return state.result();
        } catch (RuntimeException | Error failure) {
            // Completion and notification failures may occur after the irrevocable resource boundary.
            // A thrown tick is not a certified save point, even when its lifecycle already advanced.
            persistenceFailure = true;
            throw failure;
        } finally {
            ticking = false;
        }
    }

    private void advance() {
        if (state.lifecycle() == Lifecycle.NEW && !execute(RecipeStage.START)) {
            return;
        }
        if (state.progress() >= recipe.durationTicks()) {
            execute(RecipeStage.END);
            return;
        }
        if (execute(RecipeStage.TICK) && state.progress() >= recipe.durationTicks()) {
            execute(RecipeStage.END);
        }
    }

    private boolean execute(RecipeStage stage) {
        // Retirement pauses at complete stage boundaries, never inside acquisition/commit/rollback.
        if (retired()) { return false; }
        State before = state;
        State next = switch (stage) {
            case START -> new State(Lifecycle.RUNNING, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
            case TICK -> {
                double speed = attributes.value(StandardMachineAttributes.PROCESS_SPEED, 1);
                double progress = before.progress() + Math.max(0, speed);
                long ticks = Math.incrementExact(before.successfulTicks());
                // Freeze once so checkpoint acquisition and committed progress use the same advance.
                var advancing = new State(Lifecycle.RUNNING, progress, ticks, ProcessResult.SUCCESS);
                activeTick = new Tick(before.progress(), progress, before.successfulTicks(), ticks);
                yield advancing;
            }
            case END -> new State(Lifecycle.COMPLETED, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
        };
        ProcessResult result;
        try {
            result = RecipeStageExecutor.execute(this, stage, new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    state = next;
                }

                @Override
                public void rollback() {
                    state = before;
                }
            });
        } finally {
            activeTick = null;
        }
        if (result != ProcessResult.SUCCESS) {
            state = new State(result instanceof ProcessResult.Failure ? Lifecycle.FAILED : before.lifecycle(),
                before.progress(), before.successfulTicks(), result, before.started());
            if (result instanceof ProcessResult.Failure) { RecipeAbortCleanup.run(this); }
            return false;
        }
        return true;
    }

    private boolean retired() {
        var lifecycle = owner.lifecycle();
        return lifecycle != null && lifecycle.retired();
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
