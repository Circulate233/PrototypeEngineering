package com.circulation.prototype_eng.structure;

import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.api.structure.StructureAssembly;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureEvaluation;
import com.circulation.prototype_eng.api.structure.StructureHatchBinding;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import com.circulation.prototype_eng.api.structure.StructureComponentProvider;
import com.circulation.prototype_eng.api.structure.StructureComponents;
import java.util.function.BooleanSupplier;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructurePredicateResult;
import java.util.Iterator;
/**
 * PM's capture/worker/apply flow, with current-world validation and public hatch discovery.
 * One coordinator belongs to one loaded lifecycle; prepare/publish/invalidate are server-only.
 * The real scheduler must join before publication and budget capture, matching and revalidation.
 * Request evaluation is detached and may run on a worker; this class does not own an executor.
 */
public final class StructureRefresh {
    private final MachineLifecycle lifecycle;
    private final MachineStructureHost host;
    private final BooleanSupplier attachmentCurrent;
    @Nullable private Request pending;
    private boolean active;
    private List<StructureSnapshot.Failure> diagnostics = List.of();
    private StructurePredicateResult publicationStatus = StructurePredicateResult.UNAVAILABLE;
    private Object preparationOwner = new Object();
    @Nullable
    private StructureConfiguration cachedConfiguration;
    @Nullable
    private BlockPos cachedController;
    @Nullable
    private StructurePlan cachedPlan;

    public StructureRefresh(MachineLifecycle lifecycle, MachineStructureHost host) {
        this(lifecycle, host, () -> true); }

    /** Independent external attachment/owner checks remain owner-only and never enter a Request. */
    public StructureRefresh(MachineLifecycle lifecycle, MachineStructureHost host, BooleanSupplier attachmentCurrent) {
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.host = Objects.requireNonNull(host);
        this.attachmentCurrent = Objects.requireNonNull(attachmentCurrent);
        host.requireServerThread();
        if (lifecycle.host() != host) {
            throw new IllegalArgumentException("Structure and lifecycle hosts must be identical");
        }
    }

    /** Captures one detached request. Starting a newer attempt permanently retires older results. */
    @Nullable
    public Request prepare() {
        return beginPrepare(null).run();
    }

    /** Retained planning and capture keep one configuration; no live read window crosses advance. */
    @SuppressWarnings("ReturnOfInnerClass") // The retained cursor owns exact configuration and preparation authority.
    public StructureOperation<Request> beginPrepare(@Nullable StructureSnapshot.JoinedWorker worker) {
        enter();
        try {
            var owner = new Object();
            preparationOwner = owner;
            pending = null;
            var configuration = host.structureConfiguration();
            if (lifecycle.state() != MachineLifecycle.State.LOADED || configuration == null || !attachmentCurrent.getAsBoolean())
                return StructureOperation.call(() -> null);
            return new StructureOperation<>() {
                @Nullable
                private StructureOperation<StructurePlan> planning;
                @Nullable
                private StructureOperation<StructureSnapshot> capture;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    enter();
                    try {
                        if (complete()) return true;
                        if (preparationOwner != owner || !configurationCurrent(configuration)) {
                            finish(null);
                            return true;
                        }
                        if (planning == null) {
                            if (!work.take()) return false;
                            planning = beginPlan(owner, configuration);
                        }
                        if (!planning.advance(work)) return false;
                        var plan = Objects.requireNonNull(planning.result());
                        if (capture == null) {
                            if (!work.take()) return false;
                            capture = StructureSnapshot.beginCapture(plan.reads(), host, worker);
                        }
                        if (!capture.advance(work)) return false;
                        var snapshot = capture.result();
                        if (snapshot == null) {
                            finish(null);
                            return true;
                        }
                        diagnostics = snapshot.failures();
                        if (preparationOwner != owner || !configurationCurrent(configuration)) {
                            finish(null);
                            return true;
                        }
                        pending = new Request(configuration, plan, snapshot);
                        finish(pending);
                        return true;
                    } catch (RuntimeException | Error failure) {
                        lifecycle.refreshStructure(() -> preparationOwner == owner && configurationCurrent(configuration), () -> {
                            throw failure;
                        });
                        throw failure;
                    } finally {
                        active = false;
                    }
                }
            };
        } finally {
            active = false;
        }
    }

    /** Same authority and planning as beginPrepare, with an explicit externally joined read phase. */
    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains this exact refresh owner and its revocation proof.
    public ParallelPreparation beginParallelPrepare() {
        enter();
        try {
            var owner = new Object();
            preparationOwner = owner;
            pending = null;
            return new ParallelPreparation(owner, host.structureConfiguration());
        } finally { active = false; }
    }

    public final class ParallelPreparation {
        private final Object owner;
        @Nullable private final StructureConfiguration configuration;
        @Nullable private StructureOperation<StructurePlan> planning;
        @Nullable private StructureSnapshot.Capture capture;
        private boolean complete;
        @Nullable private Request result;

        private ParallelPreparation(Object owner, @Nullable StructureConfiguration configuration) {
            this.owner = owner;
            this.configuration = configuration;
        }

        public boolean complete() { host.requireServerThread(); return complete; }
        @Nullable public Request result() { host.requireServerThread(); return result; }

        @Nullable
        public Runnable prepareWorker(RecipeWorkBudget work) {
            enter();
            try {
                if (complete) return null;
                if (!current()) {
                    complete = true;
                    return null;
                }
                if (planning == null) {
                    if (!work.take()) return null;
                    planning = beginPlan(owner, Objects.requireNonNull(configuration));
                }
                if (!planning.advance(work)) return null;
                var plan = Objects.requireNonNull(planning.result());
                if (capture == null) {
                    if (!work.take()) return null;
                    capture = StructureSnapshot.beginParallelCapture(plan.reads(), host);
                }
                var action = capture.prepareWorker(work);
                if (action != null) return action;
                if (!capture.complete()) return null;
                complete = true;
                var snapshot = capture.result();
                if (snapshot != null && current()) {
                    diagnostics = snapshot.failures();
                    pending = new Request(Objects.requireNonNull(configuration), plan, snapshot);
                    result = pending;
                }
                return null;
            } catch (RuntimeException | Error failure) {
                failPreparation(failure);
                throw failure;
            } finally {
                active = false;
            }
        }

        /** Cleanup is unconditional, even when another owner's preparation revoked this request. */
        public void finishWorker(boolean accepted, @Nullable Throwable primary) {
            enter();
            try {
                Objects.requireNonNull(capture).finishWorker(accepted, primary);
                if (primary instanceof RuntimeException runtime) throw runtime;
                if (primary instanceof Error error) throw error;
                if (primary != null) throw new IllegalStateException("Structure capture worker failed", primary);
            } catch (RuntimeException | Error failure) { failPreparation(failure); throw failure; }
            finally { active = false; }
        }

        private boolean current() {
            return preparationOwner == owner && configuration != null && lifecycle.state() == MachineLifecycle.State.LOADED
                && !lifecycle.retired() && configurationCurrent(configuration);
        }

        private void failPreparation(Throwable failure) {
            lifecycle.refreshStructure(this::current, () -> {
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IllegalStateException("Structure preparation failed", failure);
            });
        }
    }

    /**
     * Revokes pending results and pauses existing bindings, at the scheduler's exclusive boundary.
     */
    public void invalidate() {
        enter();
        try {
            pending = null;
            preparationOwner = new Object();
            clearPlan();
            if (lifecycle.state() == MachineLifecycle.State.LOADED) {
                lifecycle.invalidateStructure();
            }
        } finally {
            active = false;
        }
    }

    /**
     * Old success and old failure results both leave a newer request and existing bindings alone.
     * False also requests retry if discovery lost stability; it never emits a false formed event.
     * The scheduler must finish/retry the current refresh before releasing its publication boundary.
     */
    public boolean publish(Result result) {
        return publish(result, new RecipeWorkBudget(Long.MAX_VALUE), null);
                }

    /** A complete actual owner capture, match, both proofs and install occupy this one transition. */
    public boolean publish(Result result, RecipeWorkBudget work, @Nullable StructureSnapshot.JoinedWorker worker) {
        Objects.requireNonNull(result);
        enter();
        try {
            boolean applied = lifecycle.refreshStructure(
                () -> pending == result.request && configurationCurrent(result.request.configuration),
                () -> {
                    var current = fresh(result.request.plan, work, worker);
                    if (current == null) return null;
                    publicationStatus = current.status();
                    diagnostics = current.failures();
                    return assemble(current, work, worker);
                }, work);
            if (applied && pending == result.request) {
                pending = null;
            }
            return applied;
        } finally {
            active = false;
        }
    }

    /** Includes unused-branch failures from the latest capture or publication recheck for reporting. */
    @SuppressWarnings("unused") // Scheduler diagnostics must include capture and fallback failures.
    public List<StructureSnapshot.Failure> diagnostics() { host.requireServerThread(); return diagnostics; }

    public StructurePredicateResult publicationStatus() { host.requireServerThread(); return publicationStatus; }

    /**
     * Server-side completion for a failed worker. Its owner reports the original task error even if
     * obsolete; only a still-current failure may fault this lifecycle. Errors are rethrown unchanged.
     */
    public void fail(Request request, Throwable failure) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(failure);
        enter();
        try {
            lifecycle.refreshStructure(() -> pending == request && configurationCurrent(request.configuration), () -> {
                if (failure instanceof RuntimeException runtime) { throw runtime; }
                if (failure instanceof Error error) { throw error; }
                throw new IllegalStateException("Structure worker failed", failure);
            });
        } finally { active = false; }
    }

    private void enter() {
        host.requireServerThread();
        if (active) { throw new IllegalStateException("Reentrant structure refresh"); }
        active = true;
    }

    private boolean configurationCurrent(StructureConfiguration configuration) {
        return attachmentCurrent.getAsBoolean() && host.structureConfiguration() == configuration;
    }

    /** One owner-only slot; both preparation paths use the same exact generation and position checks. */
    private StructureOperation<StructurePlan> beginPlan(Object owner, StructureConfiguration configuration) {
        var controller = host.identity().controllerPos().toImmutable();
        boolean reusable = configuration.definition().planReusable();
        var cached = cachedPlan;
        if (reusable && cached != null && cachedConfiguration == configuration && controller.equals(cachedController))
            return StructureOperation.call(() -> cached);
        clearPlan();
        var planning = StructurePlan.beginCreate(configuration, controller);
        return new StructureOperation<>() {
            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                if (!planning.advance(work)) return false;
                var plan = Objects.requireNonNull(planning.result());
                if (reusable && plan.planReusable() && preparationOwner == owner && lifecycle.state() == MachineLifecycle.State.LOADED
                    && !lifecycle.retired() && configurationCurrent(configuration)
                    && controller.equals(host.identity().controllerPos())) {
                    cachedConfiguration = configuration;
                    cachedController = controller;
                    cachedPlan = plan;
                }
                finish(plan);
                return true;
            }
        };
    }

    private void clearPlan() {
        cachedConfiguration = null;
        cachedController = null;
        cachedPlan = null;
    }

    /** Preparation really discovers each binding and port; none of these candidates authorizes work. */
    @SuppressWarnings("ReturnOfInnerClass") // The discovery cursor retains exact request authority without granting publication.
    public StructureOperation<StructureAssembly> beginDiscovery(Result result) {
        Objects.requireNonNull(result); host.requireServerThread();
        var operation = new AssemblyOperation(result.evaluation);
        return new StructureOperation<>() {
            @Override public boolean advance(RecipeWorkBudget work) {
                enter();
                try {
                    if (complete()) return true;
                    if (pending != result.request || !configurationCurrent(result.request.configuration)) { finish(null); return true; }
                    if (!operation.advance(work)) return false;
                    finish(operation.result()); return true;
                } finally { active = false; }
            }
        };
    }

    @Nullable
    private StructureAssembly assemble(StructureEvaluation evaluation, RecipeWorkBudget work, @Nullable StructureSnapshot.JoinedWorker worker) {
        var operation = new AssemblyOperation(evaluation);
        if (!operation.advance(work)) return null;
        var assembly = operation.result();
        if (assembly == null) return null;
        var proof = evaluation.isCurrent(host, work);
        if (proof == null) return null;
        if (!proof) {
            var verified = fresh(evaluation.plan(), work, worker);
            if (verified != null) diagnostics = verified.failures();
            return null;
        }
        if (assembly.match() == null) return assembly;
        for (int index = 0; index < operation.orderedPositions.size(); index++) {
            var position = operation.orderedPositions.get(index);
            if (!work.take()) return null;
            if (!host.isAreaLoaded(StructureBounds.at(position))) return null;
            if (!work.take()) return null;
            if (!operation.bindings.get(index).isCurrent(host.hatchesAt(position))) return null;
            if (!work.take()) return null;
            if (operation.providers.get(index) != host.structureComponentsAt(position)) return null;
        }
        return assembly;
    }

    private final class AssemblyOperation extends StructureOperation<StructureAssembly> {
        private final StructureEvaluation evaluation;
        @Nullable private final StructureMatch match;
        @Nullable private final StructureOperation<List<BlockPos>> collect;
        private final ObjectArrayList<BlockPos> positions = new ObjectArrayList<>();
        private List<BlockPos> orderedPositions = List.of();
        private final ObjectArrayList<StructureHatchBinding> bindings = new ObjectArrayList<>();
        private final ObjectArrayList<StructureComponentProvider> providers = new ObjectArrayList<>();
        private final ObjectArrayList<StructureComponents.Binding<?>> ports = new ObjectArrayList<>();
        private final ObjectArrayList<ResourceHatch> resources = new ObjectArrayList<>();
        private final ObjectArrayList<EnergyInput> inputs = new ObjectArrayList<>();
        private final ObjectArrayList<EnergyOutput> outputs = new ObjectArrayList<>();
        private final ReferenceOpenHashSet<ResourceHatch> seenResources = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<EnergyInput> seenInputs = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<EnergyOutput> seenOutputs = new ReferenceOpenHashSet<>();
        private final long generation;
        private int copyIndex;
        private int index;
        private int phase;
        @Nullable private StructureOperation<List<BlockPos>> sort;
        @Nullable private Iterator<StructureComponentProvider.Entry<?>> entries;
        @Nullable private StructureComponentProvider.Context context;
        @Nullable private StructureOperation<MachineHatches> freezeHatches;
        @Nullable private StructureOperation<StructureComponents> freezePorts;

        private AssemblyOperation(StructureEvaluation evaluation) {
            this.evaluation = evaluation;
            match = evaluation.match();
            collect = match == null ? null : match.beginAllBlocks();
            generation = lifecycle.formed() ? lifecycle.formationGeneration() : Math.incrementExact(lifecycle.formationGeneration());
        }

        @Override public boolean advance(RecipeWorkBudget work) {
            if (complete()) return true;
            if (evaluation.status() == StructurePredicateResult.UNAVAILABLE) { finish(null); return true; }
            if (match == null) { if (!work.take()) return false; finish(StructureAssembly.UNFORMED); return true; }
            if (!Objects.requireNonNull(collect).advance(work)) return false;
            var covered = Objects.requireNonNull(collect.result());
            while (copyIndex < covered.size()) {
                if (!work.take()) return false;
                var position = covered.get(copyIndex++);
                if (!position.equals(host.identity().controllerPos())) positions.add(position);
            }
            if (sort == null) sort = sortPositions(positions);
            if (!sort.advance(work)) return false;
            var ordered = Objects.requireNonNull(sort.result());
            orderedPositions = ordered;
            while (index < ordered.size()) {
                var position = ordered.get(index);
                if (phase == 0) {
                    if (!work.take()) return false;
                    if (!host.isAreaLoaded(StructureBounds.at(position))) { finish(null); return true; }
                    phase = 1;
                }
                if (phase == 1) {
                    if (!work.take()) return false;
                    bindings.add(Objects.requireNonNull(host.hatchesAt(position))); phase = 2;
                }
                if (phase == 2) {
                    if (!work.take()) return false;
                    providers.add(host.structureComponentsAt(position)); phase = 3;
                }
                if (phase == 3) {
                    if (!work.take()) return false;
                    var provider = providers.get(index);
                    if (provider != null) {
                        context = new StructureComponentProvider.Context(lifecycle.machine(), position, generation);
                        entries = Objects.requireNonNull(provider.createStructureComponents(context)).iterator();
                    }
                    phase = 4;
                }
                if (entries != null) {
                    while (entries.hasNext()) {
                        if (!work.take()) return false;
                        ports.add(new StructureComponents.Binding<>(Objects.requireNonNull(context), Objects.requireNonNull(entries.next())));
                    }
                    entries = null; context = null;
                }
                if (!work.take()) return false;
                var binding = bindings.get(index);
                if (binding.resource() != null && seenResources.add(binding.resource())) resources.add(binding.resource());
                if (binding.energyInput() != null && seenInputs.add(binding.energyInput())) inputs.add(binding.energyInput());
                if (binding.energyOutput() != null && seenOutputs.add(binding.energyOutput())) outputs.add(binding.energyOutput());
                index++; phase = 0;
            }
            if (freezeHatches == null) freezeHatches = MachineHatches.beginCreate(resources, inputs, outputs);
            if (!freezeHatches.advance(work)) return false;
            if (freezePorts == null) freezePorts = StructureComponents.beginCreate(ports);
            if (!freezePorts.advance(work) || !work.take()) return false;
            finish(new StructureAssembly(match, Objects.requireNonNull(freezeHatches.result()), Objects.requireNonNull(freezePorts.result())));
            return true;
        }
    }

    /** Merge comparisons and output visits resume independently, including one-unit slices. */
    private static StructureOperation<List<BlockPos>> sortPositions(List<BlockPos> positions) {
        return new StructureOperation<>() {
            private List<BlockPos> source = positions;
            private ObjectArrayList<BlockPos> output = new ObjectArrayList<>();
            private int width = 1;
            private int left;
            private int first;
            private int second;
            private int middle;
            private int end;
            private boolean merging;
            @Override public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                if (source.size() < 2) { finish(source); return true; }
                while (true) {
                    while (left < source.size()) {
                        if (!merging) {
                            first = left; middle = (int) Math.min(source.size(), (long) left + width);
                            second = middle; end = (int) Math.min(source.size(), (long) left + 2L * width); merging = true;
                        }
                        while (first < middle || second < end) {
                            if (!work.take()) return false;
                            boolean chooseFirst = second == end || first < middle && POSITION_ORDER.compare(source.get(first), source.get(second)) <= 0;
                            output.add(source.get(chooseFirst ? first++ : second++));
                        }
                        left = end; merging = false;
                    }
                    source = output;
                    if (width >= (source.size() + 1L) / 2L) { finish(source); return true; }
                    width *= 2; left = 0; output = new ObjectArrayList<>();
                }
            }
        };
    }

    private static final Comparator<BlockPos> POSITION_ORDER = Comparator.comparingInt(BlockPos::getX)
        .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ);
    @Nullable
    private StructureEvaluation fresh(StructurePlan plan, RecipeWorkBudget work, @Nullable StructureSnapshot.JoinedWorker worker) {
        var captured = StructureSnapshot.capture(plan.reads(), host, work);
        if (captured == null) return null;
        var evaluation = plan.beginEvaluate(captured);
        boolean[] completed = { false };
        Runnable action = () -> completed[0] = evaluation.advance(work);
        if (worker == null) action.run();
        else if (!worker.run(action)) return null;
        return completed[0] ? Objects.requireNonNull(evaluation.result()) : null;
    }

    /** This task contains no host, World, TileEntity, lifecycle or active hatch contract. */
    public static final class Request {
        private final StructureConfiguration configuration;
        private final StructurePlan plan;
        private final StructureSnapshot snapshot;

        private Request(StructureConfiguration configuration, StructurePlan plan, StructureSnapshot snapshot) {
            this.configuration = configuration;
            this.plan = plan;
            this.snapshot = snapshot;
        }

        /** Source diagnostics include unused optional branches; expose them to the scheduler's reporter. */
        public List<StructureSnapshot.Failure> failures() { return snapshot.failures(); }

        /** Only this operation belongs on a worker. Match failures propagate to its task owner. */
        public Result evaluate() { return new Result(this, plan.evaluate(snapshot)); }

        @SuppressWarnings("ReturnOfInnerClass") // The cursor retains only this detached request and evaluation.
        public StructureOperation<Result> beginEvaluate() {
            var evaluation = plan.beginEvaluate(snapshot);
            return new StructureOperation<>() {
                @Override public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    if (!evaluation.advance(work) || !work.take()) return false;
                    finish(new Result(Request.this, Objects.requireNonNull(evaluation.result()))); return true;
                }
            };
        }
    }

    /** Only evaluation can create a result, so a result cannot substitute another request's proof. */
    public static final class Result {
        private final Request request;
        private final StructureEvaluation evaluation;

        private Result(Request request, StructureEvaluation evaluation) {
            this.request = request;
            this.evaluation = evaluation;
        }

        public StructurePredicateResult status() { return evaluation.status(); }
    }
}
