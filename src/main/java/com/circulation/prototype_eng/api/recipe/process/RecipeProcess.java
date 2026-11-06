package com.circulation.prototype_eng.api.recipe.process;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Isolated requirement/extension state for one work slot. A scheduler may run different processes
 * concurrently but must never execute the same process concurrently. Its machine attribute baseline
 * must remain stable during that execution. Component persistence, scanning and planned operation
 * bindings are separate migration work; tick() is the synchronous single-process lifecycle.
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
    @Nullable
    private Tick activeTick;

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

    /** Frozen scaled/unscaled timeline for requirements executing the current TICK stage. */
    public Tick tickContext() {
        return Objects.requireNonNull(activeTick, "No TICK stage is executing");
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
        if (ticking) {
            throw new IllegalStateException("Recursive recipe process execution");
        }
        if (state.lifecycle() == Lifecycle.COMPLETED || state.lifecycle() == Lifecycle.FAILED) {
            return state.result();
        }
        ticking = true;
        try {
            components.tick(this, this::advance);
            return state.result();
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
                before.progress(), before.successfulTicks(), result);
            return false;
        }
        return true;
    }

    public enum Lifecycle { NEW, RUNNING, COMPLETED, FAILED }

    /** Progress is absolute scaled work ticks; successfulTicks is the unscaled deterministic clock. */
    public record State(Lifecycle lifecycle, double progress, long successfulTicks, ProcessResult result) {
        public State {
            Objects.requireNonNull(lifecycle);
            Objects.requireNonNull(result);
            if (!Double.isFinite(progress) || progress < 0 || successfulTicks < 0) {
                throw new IllegalArgumentException("Invalid process progress");
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
