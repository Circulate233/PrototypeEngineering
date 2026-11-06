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

/**
 * PM's capture/worker/apply flow, with current-world validation and public hatch discovery.
 * One coordinator belongs to one loaded lifecycle; prepare/publish/invalidate are server-only.
 * The real scheduler must join before publication and budget capture, matching and revalidation.
 * Request evaluation is detached and may run on a worker; this class does not own an executor.
 */
public final class StructureRefresh {
    private final MachineLifecycle lifecycle;
    private final MachineStructureHost host;
    @Nullable private Request pending;
    private boolean active;
    private List<StructureSnapshot.Failure> diagnostics = List.of();

    public StructureRefresh(MachineLifecycle lifecycle, MachineStructureHost host) {
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.host = Objects.requireNonNull(host);
        host.requireServerThread();
        if (lifecycle.host() != host) { throw new IllegalArgumentException("Structure and lifecycle hosts must be identical"); }
    }

    /** Captures one detached request. Starting a newer attempt permanently retires older results. */
    @Nullable
    public Request prepare() {
        enter();
        try {
            pending = null;
            var configuration = host.structureConfiguration();
            if (lifecycle.state() != MachineLifecycle.State.LOADED || configuration == null) { return null; }
            var plan = new StructurePlan(configuration.definition(), host.identity().controllerPos(), configuration.orientation());
            var snapshot = StructureSnapshot.capture(plan.reads(), host);
            diagnostics = snapshot.failures();
            // Observational external readers must not change configuration, but do not publish if they do.
            if (host.structureConfiguration() != configuration) { return null; }
            var request = new Request(configuration, plan, snapshot);
            pending = request;
            return request;
        } finally { active = false; }
    }

    /** Revokes pending results and pauses existing bindings, at the scheduler's exclusive boundary. */
    public void invalidate() {
        enter();
        try {
            pending = null;
            if (lifecycle.state() == MachineLifecycle.State.LOADED) { lifecycle.invalidateStructure(); }
        } finally { active = false; }
    }

    /**
     * Old success and old failure results both leave a newer request and existing bindings alone.
     * False also requests retry if discovery lost stability; it never emits a false formed event.
     * The scheduler must finish/retry the current refresh before releasing its publication boundary.
     */
    public boolean publish(Result result) {
        Objects.requireNonNull(result);
        enter();
        try {
            boolean applied = lifecycle.refreshStructure(
                () -> pending == result.request && host.structureConfiguration() == result.request.configuration,
                () -> {
                    var current = result.evaluation.refresh(host);
                    diagnostics = current.failures();
                    return assemble(current);
                });
            if (applied && pending == result.request) { pending = null; }
            return applied;
        } finally { active = false; }
    }

    /** Includes unused-branch failures from the latest capture or publication recheck for reporting. */
    @SuppressWarnings("unused") // Scheduler diagnostics must include capture and fallback failures.
    public List<StructureSnapshot.Failure> diagnostics() { host.requireServerThread(); return diagnostics; }

    /**
     * Server-side completion for a failed worker. Its owner reports the original task error even if
     * obsolete; only a still-current failure may fault this lifecycle. Errors are rethrown unchanged.
     */
    public void fail(Request request, Throwable failure) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(failure);
        enter();
        try {
            lifecycle.refreshStructure(() -> pending == request && host.structureConfiguration() == request.configuration, () -> {
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

    @Nullable
    private StructureAssembly assemble(StructureEvaluation evaluation) {
        var match = evaluation.match();
        if (match == null) { return StructureAssembly.UNFORMED; }
        var positions = new ObjectArrayList<>(match.allBlocks());
        positions.remove(host.identity().controllerPos());
        positions.sort(Comparator.comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
        var bindings = new ObjectArrayList<StructureHatchBinding>(positions.size());
        var resources = new ObjectArrayList<ResourceHatch>();
        var inputs = new ObjectArrayList<EnergyInput>();
        var outputs = new ObjectArrayList<EnergyOutput>();
        var seenResources = new ReferenceOpenHashSet<ResourceHatch>();
        var seenInputs = new ReferenceOpenHashSet<EnergyInput>();
        var seenOutputs = new ReferenceOpenHashSet<EnergyOutput>();
        for (var position : positions) {
            // External shapes also need a loaded, currently present discovery position.
            if (!host.isAreaLoaded(StructureBounds.at(position))) { return null; }
            var binding = Objects.requireNonNull(host.hatchesAt(position));
            bindings.add(binding);
            if (binding.resource() != null && seenResources.add(binding.resource())) { resources.add(binding.resource()); }
            if (binding.energyInput() != null && seenInputs.add(binding.energyInput())) { inputs.add(binding.energyInput()); }
            if (binding.energyOutput() != null && seenOutputs.add(binding.energyOutput())) { outputs.add(binding.energyOutput()); }
        }
        var verified = evaluation.refresh(host);
        diagnostics = verified.failures();
        if (verified != evaluation) { return null; }
        // Capabilities are external callbacks. A provider swap/invalidated contract cannot leave a
        // partial candidate; recheck absent providers as well as the hatches that were discovered.
        for (int index = 0; index < positions.size(); index++) {
            var position = positions.get(index);
            if (!host.isAreaLoaded(StructureBounds.at(position))
                || !bindings.get(index).isCurrent(host.hatchesAt(position))) { return null; }
        }
        return new StructureAssembly(match, new MachineHatches(resources, inputs, outputs));
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
    }

    /** Only evaluation can create a result, so a result cannot substitute another request's proof. */
    public static final class Result {
        private final Request request;
        private final StructureEvaluation evaluation;

        private Result(Request request, StructureEvaluation evaluation) {
            this.request = request;
            this.evaluation = evaluation;
        }
    }
}
