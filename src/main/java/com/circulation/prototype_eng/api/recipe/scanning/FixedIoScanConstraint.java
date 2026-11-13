package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * PM's fixed TICK/END admission checks using the same batch acquisition as actual execution.
 * These are independent observations of current balances, not a future lifecycle simulation.
 * Dynamic requirements and process callbacks are left to the authoritative START and later ticks.
 * An earlier input may free a shared output context, so that later stage is not rejected based on
 * its present capacity. Normal mixed hatches have independent input/output buffers and are checked.
 */
public final class FixedIoScanConstraint implements RecipeParallelismConstraint {
    private static boolean sharedResourceContext(MachineHatches hatches) {
        var sources = new ReferenceOpenHashSet<StorageContext>();
        for (var hatch : hatches.resources()) {
            if (!hatch.isValid()) {
                continue;
            }
            var input = hatch.input();
            if (input != null && input.isValid()) {
                sources.add(input.context());
            }
        }
        for (var hatch : hatches.resources()) {
            if (!hatch.isValid()) {
                continue;
            }
            var output = hatch.output();
            // All AEKey types can compete for the same physical slots.
            if (output != null && output.isValid() && sources.contains(output.context())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharedEnergyContext(MachineHatches hatches) {
        var sources = new ReferenceOpenHashSet<StorageContext>();
        for (var input : hatches.energyInputs()) {
            if (input.isValid()) {
                sources.add(input.context());
            }
        }
        for (var output : hatches.energyOutputs()) {
            if (output.isValid() && sources.contains(output.context())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Check beginCheck(RecipeProcess process) {
        return new FixedCheck(process);
    }

    @Override
    public ProcessResult test(RecipeProcess process) {
        process.requirePersistenceBoundary();
        for (var binding : process.components().snapshotBindings()) {
            if (!binding.type().systems().isEmpty()) {
                return ProcessResult.SUCCESS;
            }
        }
        var resources = Io.EMPTY;
        var energy = Io.EMPTY;
        var overlays = process.components().get(RecipeOverlays.TYPE);
        for (var requirement : process.orderedRequirements()) {
            var effective = overlays == null ? requirement : overlays.resolve(requirement);
            if (requirement.type() == RecipeRequirements.RESOURCES) {
                var payload = (ResourceRequirement) effective.payload();
                if (!payload.chance().equals(ResourceChance.ALWAYS) || payload.randomOutputs() != null
                    || !payload.fuzzyInputs().isEmpty() || !payload.dynamicInputs().isEmpty()) {
                    return ProcessResult.SUCCESS;
                }
                resources = resources.merge(new Io(!payload.inputs().isEmpty(), !payload.inputsPerTick().isEmpty(),
                    !payload.outputsPerTick().isEmpty(), !payload.outputs().isEmpty()));
            } else if (requirement.type() == RecipeRequirements.ENERGY) {
                var payload = (EnergyRequirement) effective.payload();
                energy = energy.merge(new Io(payload.input() > 0, payload.inputPerTick() > 0,
                    payload.outputPerTick() > 0, payload.output() > 0));
            } else {
                // Earlier requirements may change later IO, attributes or process-local state.
                return ProcessResult.SUCCESS;
            }
        }
        boolean ticks = process.recipe().durationTicks() > 0;
        boolean checkTick = ticks && (resources.needsTick() || energy.needsTick());
        boolean checkEnd = resources.endOutput || energy.endOutput;
        if (!checkTick && !checkEnd) {
            return ProcessResult.SUCCESS;
        }
        var hatches = process.hatches();
        boolean sharedResources = resources.mayFreeOutput(ticks) && sharedResourceContext(hatches);
        boolean sharedEnergy = energy.mayFreeOutput(ticks) && sharedEnergyContext(hatches);
        if (checkTick && !resources.freesTickOutput(sharedResources) && !energy.freesTickOutput(sharedEnergy)) {
            var result = RecipeStageExecutor.probeStage(process, RecipeStage.TICK);
            if (result != ProcessResult.SUCCESS) {
                return result;
            }
        }
        if (checkEnd && !resources.freesEndOutput(sharedResources, ticks) && !energy.freesEndOutput(sharedEnergy, ticks)) {
            return RecipeStageExecutor.probeStage(process, RecipeStage.END);
        }
        return ProcessResult.SUCCESS;
    }

    private static final class FixedCheck implements Check {
        private final RecipeProcess process;
        private final ReferenceOpenHashSet<StorageContext> resourceInputs = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<StorageContext> energyInputs = new ReferenceOpenHashSet<>();
        private List<ComponentMap.SavedBinding<RecipeProcess, ?>> bindings;
        private ComponentMap<RecipeProcess>.BindingCapture bindingCapture;
        private int bindingCursor;
        private int requirementCursor;
        private Io resources = Io.EMPTY;
        private Io energy = Io.EMPTY;
        private boolean ticks;
        private boolean checkTick;
        private boolean checkEnd;
        private boolean sharedResources;
        private boolean sharedEnergy;
        private MachineHatches hatches;
        private int hatchCursor;
        private int phase;
        private boolean cancelled;
        private ProcessResult result = ProcessResult.SUCCESS;
        @Nullable
        private RecipeStageExecutor.Probe probe;

        private FixedCheck(RecipeProcess process) {
            process.requirePersistenceBoundary();
            this.process = process;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
            if (phase == 8) return true;
            if (cancelled || !forwardAllowed.getAsBoolean()) {
                cancelled = true;
                result = RecipeText.Cancelled.blocked();
                if (probe != null && !probe.finished()) {
                    probe.cancel();
                    if (!probe.advance(budget, () -> false)) return false;
                }
                phase = 8;
                return true;
            }
            if (phase == 0) {
                if (bindings == null) {
                    if (bindingCapture == null) {
                        if (!budget.take()) return false;
                        bindingCapture = process.components().beginSnapshotBindings();
                    }
                    if (!bindingCapture.advance(budget)) {
                        if (!bindingCapture.current()) bindingCapture = null;
                        return false;
                    }
                    bindings = bindingCapture.result();
                }
                while (bindingCursor < bindings.size()) {
                    if (!budget.take()) return false;
                    if (!bindings.get(bindingCursor++).type().systems().isEmpty()) {
                        phase = 8;
                        return true;
                    }
                }
                phase = 1;
            }
            if (phase == 1) {
                var overlays = process.components().get(RecipeOverlays.TYPE);
                List<RecipeRequirement<?>> requirements = process.orderedRequirements();
                while (requirementCursor < requirements.size()) {
                    if (!budget.take()) return false;
                    var requirement = requirements.get(requirementCursor++);
                    var effective = overlays == null ? requirement : overlays.resolve(requirement);
                    if (requirement.type() == RecipeRequirements.RESOURCES) {
                        var payload = (ResourceRequirement) effective.payload();
                        if (!payload.chance().equals(ResourceChance.ALWAYS) || payload.randomOutputs() != null
                            || !payload.fuzzyInputs().isEmpty() || !payload.dynamicInputs().isEmpty()) {
                            phase = 8;
                            return true;
                        }
                        resources = resources.merge(new Io(!payload.inputs().isEmpty(), !payload.inputsPerTick().isEmpty(),
                            !payload.outputsPerTick().isEmpty(), !payload.outputs().isEmpty()));
                    } else if (requirement.type() == RecipeRequirements.ENERGY) {
                        var payload = (EnergyRequirement) effective.payload();
                        energy = energy.merge(new Io(payload.input() > 0, payload.inputPerTick() > 0,
                            payload.outputPerTick() > 0, payload.output() > 0));
                    } else {
                        phase = 8;
                        return true;
                    }
                }
                ticks = process.recipe().durationTicks() > 0;
                checkTick = ticks && (resources.needsTick() || energy.needsTick());
                checkEnd = resources.endOutput || energy.endOutput;
                if (!checkTick && !checkEnd) {
                    phase = 8;
                    return true;
                }
                hatches = process.hatches();
                phase = 2;
            }
            if (phase == 2) {
                if (resources.mayFreeOutput(ticks)) {
                    while (hatchCursor < hatches.resources().size()) {
                        if (!budget.take()) return false;
                        var hatch = hatches.resources().get(hatchCursor++);
                        var input = hatch.input();
                        if (hatch.isValid() && input != null && input.isValid()) resourceInputs.add(input.context());
                    }
                }
                hatchCursor = 0;
                phase = 3;
            }
            if (phase == 3) {
                if (resources.mayFreeOutput(ticks)) {
                    while (hatchCursor < hatches.resources().size()) {
                        if (!budget.take()) return false;
                        var hatch = hatches.resources().get(hatchCursor++);
                        var output = hatch.output();
                        if (hatch.isValid() && output != null && output.isValid() && resourceInputs.contains(output.context())) {
                            sharedResources = true;
                            break;
                        }
                    }
                }
                hatchCursor = 0;
                phase = 4;
            }
            if (phase == 4) {
                if (energy.mayFreeOutput(ticks)) {
                    while (hatchCursor < hatches.energyInputs().size()) {
                        if (!budget.take()) return false;
                        var input = hatches.energyInputs().get(hatchCursor++);
                        if (input.isValid()) energyInputs.add(input.context());
                    }
                }
                hatchCursor = 0;
                phase = 5;
            }
            if (phase == 5) {
                if (energy.mayFreeOutput(ticks)) {
                    while (hatchCursor < hatches.energyOutputs().size()) {
                        if (!budget.take()) return false;
                        var output = hatches.energyOutputs().get(hatchCursor++);
                        if (output.isValid() && energyInputs.contains(output.context())) {
                            sharedEnergy = true;
                            break;
                        }
                    }
                }
                phase = 6;
            }
            if (phase == 6) {
                if (checkTick && !resources.freesTickOutput(sharedResources) && !energy.freesTickOutput(sharedEnergy)) {
                    if (!advanceStage(RecipeStage.TICK, budget, forwardAllowed)) return false;
                    if (result != ProcessResult.SUCCESS) {
                        phase = 8;
                        return true;
                    }
                }
                phase = 7;
            }
            if (checkEnd && !resources.freesEndOutput(sharedResources, ticks) && !energy.freesEndOutput(sharedEnergy, ticks)) {
                if (!advanceStage(RecipeStage.END, budget, forwardAllowed)) return false;
            }
            phase = 8;
            return true;
        }

        private boolean advanceStage(RecipeStage stage, RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
            if (probe == null) {
                if (!budget.take()) return false;
                probe = RecipeStageExecutor.beginProbe(process, stage);
            }
            if (!probe.advance(budget, forwardAllowed)) return false;
            result = probe.result();
            probe = null;
            return true;
        }

        @Override
        public ProcessResult result() {
            if (phase != 8) throw new IllegalStateException("Fixed IO constraint is incomplete");
            return result;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public @Nullable RecipePureComputation computation() {
            return probe == null ? null : probe.computation();
        }

        @Override
        public void finishComputation(RecipePureComputation expected) {
            if (probe == null) throw new IllegalStateException("No fixed IO probe");
            probe.finishComputation(expected);
        }

        @Override
        public List<ResourceBatch.Source> sources() {
            return probe == null ? List.of() : probe.sources();
        }

        @Override
        public boolean resolvingSources() {
            return probe != null && probe.resolvingSources();
        }

        @Override
        public void finishSourceResolution(ResourceBatch.Source expected, boolean resolved) {
            if (probe == null) throw new IllegalStateException("No fixed IO source action");
            probe.finishSourceResolution(expected, resolved);
        }

        @Override
        public boolean hasObligations() {
            return probe != null;
        }
    }

    private record Io(boolean startInput, boolean tickInput, boolean tickOutput, boolean endOutput) {
        private static final Io EMPTY = new Io(false, false, false, false);

        private Io merge(Io other) {
            return new Io(startInput || other.startInput, tickInput || other.tickInput,
                tickOutput || other.tickOutput, endOutput || other.endOutput);
        }

        private boolean needsTick() {
            return tickInput || tickOutput;
        }

        private boolean mayFreeOutput(boolean ticks) {
            return (startInput || ticks && tickInput) && (tickOutput || endOutput);
        }

        private boolean freesTickOutput(boolean shared) {
            return shared && startInput && tickOutput;
        }

        private boolean freesEndOutput(boolean shared, boolean ticks) {
            return shared && endOutput && (startInput || ticks && tickInput);
        }
    }
}
